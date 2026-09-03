use std::cmp::max;
use std::collections::BTreeMap;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::Arc;
use std::thread::{self, JoinHandle};
use std::time::{Instant, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};

use crate::protocol::{self, MAX_PAYLOAD_LENGTH};

const METADATA_NAME: &str = "metadata";
const FIRST_SEGMENT: u64 = 1;
const MAX_CBOR_DEPTH: usize = 64;
const MAX_RECORD_FIELDS: usize = 1024;
const MAX_ENCODED_RECORD_LENGTH: usize = MAX_PAYLOAD_LENGTH + 4096;
const MAX_DECOMPRESSED_SEGMENT_LENGTH: u64 = 512 * 1024 * 1024;
pub const DEFAULT_JOURNAL_SEGMENT_BYTES: u64 = 64 * 1024 * 1024;
pub const DEFAULT_JOURNAL_MAX_BYTES: u64 = 1024 * 1024 * 1024;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Durability {
    Buffered,
    EveryRecord,
}

#[derive(Clone, Debug)]
pub struct JournalConfig {
    pub durability: Durability,
    pub segment_max_bytes: u64,
    pub journal_max_bytes: u64,
}

impl Default for JournalConfig {
    fn default() -> Self {
        Self {
            durability: Durability::Buffered,
            segment_max_bytes: DEFAULT_JOURNAL_SEGMENT_BYTES,
            journal_max_bytes: DEFAULT_JOURNAL_MAX_BYTES,
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct JournalEvent {
    pub event_id: u64,
    pub event_type: u16,
    pub payload: Vec<u8>,
    pub encoded_payload: Vec<u8>,
    pub encoded_record: Vec<u8>,
    pub trailing_field_count: usize,
    pub opaque: bool,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct RetentionGap {
    pub requested_event_id: u64,
    pub first_available_event_id: u64,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ReadResult {
    pub events: Vec<JournalEvent>,
    pub gap: Option<RetentionGap>,
    pub ignored_crash_tail: bool,
}

#[derive(Debug)]
pub enum JournalError {
    Io(io::Error),
    Configuration(String),
    Format(String),
    Maintenance(String),
    EventIdExhausted,
    PayloadTooLarge(usize),
}

impl std::fmt::Display for JournalError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Io(error) => write!(formatter, "journal I/O error: {error}"),
            Self::Configuration(message) => {
                write!(formatter, "invalid journal configuration: {message}")
            }
            Self::Format(message) => write!(formatter, "invalid journal format: {message}"),
            Self::Maintenance(message) => write!(formatter, "journal maintenance failed: {message}"),
            Self::EventIdExhausted => formatter.write_str("journal event ID is exhausted"),
            Self::PayloadTooLarge(length) => {
                write!(formatter, "journal payload exceeds 16 MiB: {length}")
            }
        }
    }
}

// Active segment numbers make stale reconciliation commands harmless: FIFO delivery eventually
// supplies the newest boundary, and Finish always carries the final boundary before shutdown.
enum MaintenanceCommand {
    Reconcile(u64),
    Finish(u64),
}

trait MaintenanceFileSystem: Send + Sync {
    fn rename(&self, source: &Path, target: &Path) -> io::Result<()>;

    fn remove_file(&self, path: &Path) -> io::Result<()>;
}

struct RealMaintenanceFileSystem;

impl MaintenanceFileSystem for RealMaintenanceFileSystem {
    fn rename(&self, source: &Path, target: &Path) -> io::Result<()> {
        fs::rename(source, target)
    }

    fn remove_file(&self, path: &Path) -> io::Result<()> {
        fs::remove_file(path)
    }
}

struct JournalMaintenance {
    sender: Sender<MaintenanceCommand>,
    thread: Option<JoinHandle<Result<(), JournalError>>>,
}

impl JournalMaintenance {
    fn start_with_file_system(
        directory: PathBuf,
        active_segment: u64,
        durability: Durability,
        journal_max_bytes: u64,
        file_system: Arc<dyn MaintenanceFileSystem>,
    ) -> Result<Self, JournalError> {
        let (sender, receiver) = mpsc::channel();
        sender
            .send(MaintenanceCommand::Reconcile(active_segment))
            .map_err(|_| JournalError::Maintenance("cannot schedule initial reconciliation".to_owned()))?;
        let thread = thread::Builder::new()
            .name("session-journal-maintenance".to_owned())
            .spawn(move || {
                run_maintenance(
                    &directory,
                    durability,
                    journal_max_bytes,
                    file_system,
                    receiver,
                )
            })?;
        Ok(Self {
            sender,
            thread: Some(thread),
        })
    }

    fn reconcile(&self, active_segment: u64) {
        let _ = self.sender.send(MaintenanceCommand::Reconcile(active_segment));
    }

    fn ensure_running(&self) -> Result<(), JournalError> {
        if self.thread.is_none() {
            return Err(JournalError::Maintenance(
                "maintenance has already finished".to_owned(),
            ));
        }
        Ok(())
    }

    fn finish(&mut self, active_segment: u64) -> Result<(), JournalError> {
        let Some(thread) = self.thread.take() else {
            return Ok(());
        };
        let _ = self.sender.send(MaintenanceCommand::Finish(active_segment));
        match thread.join() {
            Ok(result) => result,
            Err(_) => Err(JournalError::Maintenance(
                "maintenance thread panicked".to_owned(),
            )),
        }
    }
}

impl std::error::Error for JournalError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Io(error) => Some(error),
            _ => None,
        }
    }
}

impl From<io::Error> for JournalError {
    fn from(error: io::Error) -> Self {
        Self::Io(error)
    }
}

pub struct JournalWriter {
    directory: PathBuf,
    file: File,
    journal_id: [u8; 16],
    previous_event_id: u64,
    segment_number: u64,
    active_length: u64,
    session_start: Instant,
    config: JournalConfig,
    maintenance: JournalMaintenance,
}

impl JournalWriter {
    pub fn create(
        directory: impl AsRef<Path>,
        journal_id: [u8; 16],
        config: JournalConfig,
    ) -> Result<Self, JournalError> {
        Self::create_with_maintenance_file_system(
            directory,
            journal_id,
            config,
            Arc::new(RealMaintenanceFileSystem),
        )
    }

    fn create_with_maintenance_file_system(
        directory: impl AsRef<Path>,
        journal_id: [u8; 16],
        config: JournalConfig,
        file_system: Arc<dyn MaintenanceFileSystem>,
    ) -> Result<Self, JournalError> {
        validate_config(&config)?;
        let directory = directory.as_ref().to_path_buf();
        fs::create_dir_all(&directory)?;
        let segment_number = FIRST_SEGMENT;
        let file = create_segment(&directory, segment_number, config.durability)?;
        let maintenance = JournalMaintenance::start_with_file_system(
            directory.clone(),
            segment_number,
            config.durability,
            config.journal_max_bytes,
            file_system,
        )?;
        Ok(Self {
            directory,
            file,
            journal_id,
            previous_event_id: 0,
            segment_number,
            active_length: 0,
            session_start: Instant::now(),
            config,
            maintenance,
        })
    }

    pub fn recover(
        directory: impl AsRef<Path>,
        journal_id: [u8; 16],
        config: JournalConfig,
    ) -> Result<Self, JournalError> {
        Self::recover_with_maintenance_file_system(
            directory,
            journal_id,
            config,
            Arc::new(RealMaintenanceFileSystem),
        )
    }

    fn recover_with_maintenance_file_system(
        directory: impl AsRef<Path>,
        journal_id: [u8; 16],
        config: JournalConfig,
        file_system: Arc<dyn MaintenanceFileSystem>,
    ) -> Result<Self, JournalError> {
        validate_config(&config)?;
        let directory = directory.as_ref().to_path_buf();
        let segments = discover_segments(&directory)?;
        let Some(active) = segments.last() else {
            return Self::create_with_maintenance_file_system(
                directory,
                journal_id,
                config,
                file_system,
            );
        };
        if active.compressed {
            return Err(JournalError::Format(
                "the newest journal segment must be active and uncompressed".to_owned(),
            ));
        }
        let mut previous_event_id = 0;
        for segment in &segments[..segments.len() - 1] {
            previous_event_id = scan_path(segment, 0, false, previous_event_id)?.last_event_id;
        }
        let scan = scan_path(active, 0, true, previous_event_id)?;
        previous_event_id = scan.last_event_id;
        let mut file = OpenOptions::new().read(true).write(true).open(&active.path)?;
        let length = file.metadata()?.len();
        if scan.last_boundary < length {
            file.set_len(scan.last_boundary)?;
            if config.durability == Durability::EveryRecord {
                file.sync_data()?;
            }
        }
        file.seek(SeekFrom::End(0))?;
        let maintenance = JournalMaintenance::start_with_file_system(
            directory.clone(),
            active.number,
            config.durability,
            config.journal_max_bytes,
            file_system,
        )?;
        Ok(Self {
            directory,
            file,
            journal_id,
            previous_event_id,
            segment_number: active.number,
            active_length: scan.last_boundary,
            session_start: Instant::now(),
            config,
            maintenance,
        })
    }

    pub fn journal_id(&self) -> [u8; 16] {
        self.journal_id
    }

    pub fn append(
        &mut self,
        event_type: u16,
        payload_schema_version: u16,
        flags: u32,
        payload: &[u8],
    ) -> Result<u64, JournalError> {
        let elapsed = self.session_start.elapsed().as_nanos();
        let raw_event_id = u64::try_from(elapsed).unwrap_or(u64::MAX);
        self.append_at(raw_event_id, event_type, payload_schema_version, flags, payload)
    }

    pub fn append_at(
        &mut self,
        raw_event_id: u64,
        event_type: u16,
        payload_schema_version: u16,
        flags: u32,
        payload: &[u8],
    ) -> Result<u64, JournalError> {
        self.maintenance.ensure_running()?;
        if payload.len() > MAX_PAYLOAD_LENGTH {
            return Err(JournalError::PayloadTooLarge(payload.len()));
        }
        if payload_schema_version != 1 || flags != 0 {
            return Err(JournalError::Format(
                "legacy payload schema versions and event flags are not part of CBOR journal records"
                    .to_owned(),
            ));
        }
        let next = self
            .previous_event_id
            .checked_add(1)
            .ok_or(JournalError::EventIdExhausted)?;
        let event_id = max(raw_event_id, next);
        let record = encode_event(event_id, event_type, payload)?;
        let record_length = u64::try_from(record.len())
            .map_err(|_| JournalError::Format("journal record length exceeds u64".to_owned()))?;
        let next_length = self
            .active_length
            .checked_add(record_length)
            .ok_or_else(|| JournalError::Format("active segment length overflow".to_owned()))?;
        if self.active_length != 0 && next_length > self.config.segment_max_bytes {
            self.rotate()?;
        }
        self.file.write_all(&record)?;
        if self.config.durability == Durability::EveryRecord {
            self.file.sync_data()?;
        }
        self.active_length = if self.active_length == 0 {
            record_length
        } else {
            next_length
        };
        self.previous_event_id = event_id;
        Ok(event_id)
    }

    pub fn rotate(&mut self) -> Result<(), JournalError> {
        self.maintenance.ensure_running()?;
        self.file.flush()?;
        if self.config.durability == Durability::EveryRecord {
            self.file.sync_data()?;
        }
        let number = self
            .segment_number
            .checked_add(1)
            .ok_or_else(|| JournalError::Format("segment number is exhausted".to_owned()))?;
        let file = create_segment(&self.directory, number, self.config.durability)?;
        self.file = file;
        self.segment_number = number;
        self.active_length = 0;
        self.maintenance.reconcile(number);
        Ok(())
    }

    pub fn flush(&mut self) -> Result<(), JournalError> {
        self.file.flush()?;
        Ok(())
    }

    pub fn directory(&self) -> &Path {
        &self.directory
    }

    pub fn active_segment_number(&self) -> u64 {
        self.segment_number
    }

    pub fn first_event_id(&self) -> Option<u64> {
        discover_segments(&self.directory)
            .and_then(|segments| first_available_event_id(&segments))
            .ok()
            .flatten()
    }

    pub fn latest_event_id(&self) -> Option<u64> {
        (self.previous_event_id != 0).then_some(self.previous_event_id)
    }

    pub fn finish_maintenance(&mut self) -> Result<(), JournalError> {
        self.maintenance.finish(self.segment_number)
    }
}

impl Drop for JournalWriter {
    fn drop(&mut self) {
        let _ = self.finish_maintenance();
    }
}

fn run_maintenance(
    directory: &Path,
    durability: Durability,
    journal_max_bytes: u64,
    file_system: Arc<dyn MaintenanceFileSystem>,
    receiver: Receiver<MaintenanceCommand>,
) -> Result<(), JournalError> {
    let mut pending_error = None;
    while let Ok(command) = receiver.recv() {
        let (active_segment, finish) = match command {
            MaintenanceCommand::Reconcile(active_segment) => (active_segment, false),
            MaintenanceCommand::Finish(active_segment) => (active_segment, true),
        };
        let result = reconcile_journal(
            directory,
            active_segment,
            durability,
            journal_max_bytes,
            file_system.as_ref(),
        );
        if finish {
            return result;
        }
        match result {
            Ok(()) => pending_error = None,
            Err(error) => pending_error = Some(error),
        }
    }
    match pending_error {
        Some(error) => Err(error),
        None => Ok(()),
    }
}

fn reconcile_journal(
    directory: &Path,
    active_segment: u64,
    durability: Durability,
    journal_max_bytes: u64,
    file_system: &dyn MaintenanceFileSystem,
) -> Result<(), JournalError> {
    compress_closed_segments(directory, active_segment, durability, file_system)?;
    enforce_retention(
        directory,
        active_segment,
        durability,
        journal_max_bytes,
        file_system,
    )
}

fn compress_closed_segments(
    directory: &Path,
    active_segment: u64,
    durability: Durability,
    file_system: &dyn MaintenanceFileSystem,
) -> Result<(), JournalError> {
    let mut raw_segments = Vec::new();
    for entry in fs::read_dir(directory)? {
        let entry = entry?;
        let Some((number, compressed)) = segment_number(&entry.file_name()) else {
            continue;
        };
        if !compressed && number < active_segment {
            raw_segments.push((number, entry.path()));
        }
    }
    raw_segments.sort_by_key(|(number, _)| *number);
    for (number, raw_path) in raw_segments {
        reconcile_closed_segment(
            directory,
            number,
            &raw_path,
            durability,
            file_system,
        )?;
    }
    Ok(())
}

fn reconcile_closed_segment(
    directory: &Path,
    number: u64,
    raw_path: &Path,
    durability: Durability,
    file_system: &dyn MaintenanceFileSystem,
) -> Result<(), JournalError> {
    let temporary = compressed_temporary_segment_path(directory, number);
    if temporary.try_exists()? {
        file_system.remove_file(&temporary)?;
        sync_directory_if_required(directory, durability)?;
    }
    let compressed = compressed_segment_path(directory, number);
    if compressed.try_exists()? {
        if published_compression_matches_raw(&compressed, raw_path)? {
            file_system.remove_file(raw_path)?;
            sync_directory_if_required(directory, durability)?;
            return Ok(());
        }
        file_system.remove_file(&compressed)?;
        sync_directory_if_required(directory, durability)?;
    }
    compress_segment(directory, number, raw_path, durability, file_system)
}

fn published_compression_matches_raw(
    compressed_path: &Path,
    raw_path: &Path,
) -> Result<bool, JournalError> {
    let compressed = File::open(compressed_path)?;
    let mut decoder = match zstd::stream::read::Decoder::new(compressed) {
        Ok(decoder) => decoder,
        Err(_) => return Ok(false),
    };
    let mut raw = File::open(raw_path)?;
    Ok(decoded_stream_matches_raw(
        &mut decoder,
        &mut raw,
        MAX_DECOMPRESSED_SEGMENT_LENGTH,
    )?)
}

fn decoded_stream_matches_raw(
    decoded: &mut impl Read,
    raw: &mut impl Read,
    maximum_decoded_length: u64,
) -> io::Result<bool> {
    let mut decoded_buffer = [0_u8; 8192];
    let mut raw_buffer = [0_u8; 8192];
    let mut decoded_length = 0_u64;
    loop {
        let length = match decoded.read(&mut decoded_buffer) {
            Ok(length) => length,
            Err(_) => return Ok(false),
        };
        if length == 0 {
            let mut extra = [0_u8; 1];
            return Ok(raw.read(&mut extra)? == 0);
        }
        let Some(next_length) = decoded_length.checked_add(length as u64) else {
            return Ok(false);
        };
        if next_length > maximum_decoded_length {
            return Ok(false);
        }
        decoded_length = next_length;
        match raw.read_exact(&mut raw_buffer[..length]) {
            Ok(()) => {}
            Err(error) if error.kind() == io::ErrorKind::UnexpectedEof => return Ok(false),
            Err(error) => return Err(error),
        }
        if decoded_buffer[..length] != raw_buffer[..length] {
            return Ok(false);
        }
    }
}

fn sync_directory_if_required(
    directory: &Path,
    durability: Durability,
) -> Result<(), JournalError> {
    if durability == Durability::EveryRecord {
        sync_directory(directory)?;
    }
    Ok(())
}

fn compress_segment(
    directory: &Path,
    number: u64,
    raw_path: &Path,
    durability: Durability,
    file_system: &dyn MaintenanceFileSystem,
) -> Result<(), JournalError> {
    let mut raw = File::open(raw_path)?;
    let temporary = compressed_temporary_segment_path(directory, number);
    let output = OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .open(&temporary)?;
    let mut encoder = zstd::stream::write::Encoder::new(output, 3)?;
    io::copy(&mut raw, &mut encoder)?;
    let output = encoder.finish()?;
    if durability == Durability::EveryRecord {
        output.sync_all()?;
    }
    file_system.rename(&temporary, &compressed_segment_path(directory, number))?;
    if durability == Durability::EveryRecord {
        sync_directory(directory)?;
    }
    file_system.remove_file(raw_path)?;
    if durability == Durability::EveryRecord {
        sync_directory(directory)?;
    }
    Ok(())
}

fn validate_config(config: &JournalConfig) -> Result<(), JournalError> {
    if config.segment_max_bytes == 0 || config.journal_max_bytes < config.segment_max_bytes {
        return Err(JournalError::Configuration(
            "journal limits must be positive and max must cover one segment".to_owned(),
        ));
    }
    Ok(())
}

fn create_segment(
    directory: &Path,
    number: u64,
    durability: Durability,
) -> Result<File, JournalError> {
    let file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(segment_path(directory, number))?;
    if durability == Durability::EveryRecord {
        file.sync_data()?;
        sync_directory(directory)?;
    }
    Ok(file)
}

fn encode_event(event_id: u64, event_type: u16, payload: &[u8]) -> Result<Vec<u8>, JournalError> {
    let encoded = match event_type {
        protocol::event_type::PTY_OUTPUT => protocol::encode_pty_output(event_id, payload),
        protocol::event_type::PTY_INPUT => {
            if payload.len() < 16 {
                return Err(JournalError::Format("PTY_INPUT payload is shorter than its UUID".to_owned()));
            }
            let command_id = format_uuid(payload[..16].try_into().unwrap());
            protocol::encode_pty_input(event_id, &command_id, &payload[16..])
        }
        protocol::event_type::PTY_RESIZE => {
            if payload.len() != 8 {
                return Err(JournalError::Format("PTY_RESIZE payload must be 8 bytes".to_owned()));
            }
            protocol::encode_pty_resize(
                event_id,
                u32::from_le_bytes(payload[0..4].try_into().unwrap()),
                u32::from_le_bytes(payload[4..8].try_into().unwrap()),
            )
        }
        protocol::event_type::PROCESS_STARTED => {
            if payload.len() != 8 {
                return Err(JournalError::Format("PROCESS_STARTED payload must be 8 bytes".to_owned()));
            }
            protocol::encode_process_started(
                event_id,
                u64::from_le_bytes(payload.try_into().unwrap()),
            )
        }
        protocol::event_type::PROCESS_EXITED => {
            if payload.len() != 8 {
                return Err(JournalError::Format("PROCESS_EXITED payload must be 8 bytes".to_owned()));
            }
            Ok(protocol::encode_process_exited(
                event_id,
                i32::from_le_bytes(payload[0..4].try_into().unwrap()),
            ))
        }
        protocol::event_type::SIGNAL => {
            if payload.len() != 8 {
                return Err(JournalError::Format("SIGNAL payload must be 8 bytes".to_owned()));
            }
            let flags = u16::from_le_bytes(payload[2..4].try_into().unwrap());
            if flags != 0 {
                return Err(JournalError::Format("SIGNAL flags must be zero".to_owned()));
            }
            protocol::encode_signal(
                event_id,
                u16::from_le_bytes(payload[0..2].try_into().unwrap()),
                i32::from_le_bytes(payload[4..8].try_into().unwrap()),
            )
        }
        _ => protocol::encode_binary_event(event_id, event_type, payload),
    };
    encoded.map_err(|error| match error {
        protocol::EncodeError::PayloadTooLarge { .. } => JournalError::PayloadTooLarge(payload.len()),
        protocol::EncodeError::InvalidPayload(message) => JournalError::Format(message.to_owned()),
    })
}

pub fn read(directory: impl AsRef<Path>, event_id: u64) -> Result<ReadResult, JournalError> {
    read_after(directory, event_id)
}

pub fn read_after(
    directory: impl AsRef<Path>,
    requested_event_id: u64,
) -> Result<ReadResult, JournalError> {
    read_after_with_hook(directory.as_ref(), requested_event_id, |_, _| {})
}

fn read_after_with_hook(
    directory: &Path,
    requested_event_id: u64,
    mut after_discovery: impl FnMut(usize, &[SegmentFile]),
) -> Result<ReadResult, JournalError> {
    let first = read_after_snapshot(
        directory,
        requested_event_id,
        0,
        &mut after_discovery,
    );
    if matches!(
        &first,
        Err(JournalError::Io(error)) if error.kind() == io::ErrorKind::NotFound
    ) {
        return read_after_snapshot(
            directory,
            requested_event_id,
            1,
            &mut after_discovery,
        );
    }
    first
}

fn read_after_snapshot(
    directory: &Path,
    requested_event_id: u64,
    attempt: usize,
    after_discovery: &mut impl FnMut(usize, &[SegmentFile]),
) -> Result<ReadResult, JournalError> {
    let segments = discover_segments(directory)?;
    after_discovery(attempt, &segments);
    let first_event_ids = discover_first_event_ids(&segments)?;
    let first_available = first_event_ids.iter().flatten().next().copied();
    let gap = first_available
        .filter(|first| requested_event_id < *first)
        .map(|first| RetentionGap {
            requested_event_id,
            first_available_event_id: first,
        });
    let mut events = Vec::new();
    let mut ignored_crash_tail = false;
    let mut previous_event_id = 0;
    let mut start = 0;
    for (index, first) in first_event_ids.iter().enumerate() {
        if first.is_some_and(|id| id <= requested_event_id) {
            start = index;
        }
    }
    for (index, segment) in segments.iter().enumerate().skip(start) {
        let allow_tail = index + 1 == segments.len() && !segment.compressed;
        let scan = scan_path(segment, requested_event_id, allow_tail, previous_event_id)?;
        previous_event_id = scan.last_event_id;
        events.extend(scan.events);
        ignored_crash_tail |= scan.ignored_crash_tail;
    }
    Ok(ReadResult {
        events,
        gap,
        ignored_crash_tail,
    })
}

#[derive(Clone, Debug)]
struct SegmentFile {
    number: u64,
    path: PathBuf,
    compressed: bool,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct SegmentSize {
    number: u64,
    physical_bytes: u64,
    active: bool,
}

impl SegmentSize {
    fn closed(number: u64, physical_bytes: u64) -> Self {
        Self {
            number,
            physical_bytes,
            active: false,
        }
    }

    fn active(number: u64, physical_bytes: u64) -> Self {
        Self {
            number,
            physical_bytes,
            active: true,
        }
    }
}

fn retention_deletions(
    segments: &[SegmentSize],
    journal_max_bytes: u64,
) -> Result<Vec<u64>, JournalError> {
    let mut total = 0_u64;
    for segment in segments {
        total = total.checked_add(segment.physical_bytes).ok_or_else(|| {
            JournalError::Maintenance("physical journal size overflow".to_owned())
        })?;
    }
    let mut deletions = Vec::new();
    for segment in segments {
        if total <= journal_max_bytes || segment.active {
            break;
        }
        total -= segment.physical_bytes;
        deletions.push(segment.number);
    }
    Ok(deletions)
}

fn enforce_retention(
    directory: &Path,
    active_segment: u64,
    durability: Durability,
    journal_max_bytes: u64,
    file_system: &dyn MaintenanceFileSystem,
) -> Result<(), JournalError> {
    let segments = discover_segments(directory)?;
    let mut sizes = Vec::new();
    for segment in &segments {
        if segment.number > active_segment {
            break;
        }
        let physical_bytes = fs::metadata(&segment.path)?.len();
        if segment.number == active_segment {
            sizes.push(SegmentSize::active(segment.number, physical_bytes));
        } else {
            sizes.push(SegmentSize::closed(segment.number, physical_bytes));
        }
    }
    let deletions = retention_deletions(&sizes, journal_max_bytes)?;
    for number in deletions {
        let segment = segments
            .iter()
            .find(|segment| segment.number == number)
            .ok_or_else(|| JournalError::Maintenance("retention segment disappeared".to_owned()))?;
        file_system.remove_file(&segment.path)?;
        sync_directory_if_required(directory, durability)?;
    }
    Ok(())
}

fn discover_segments(directory: &Path) -> Result<Vec<SegmentFile>, JournalError> {
    let mut candidates = BTreeMap::<u64, (Option<PathBuf>, Option<PathBuf>)>::new();
    for entry in fs::read_dir(directory)? {
        let entry = entry?;
        let Some((number, compressed)) = segment_number(&entry.file_name()) else {
            continue;
        };
        let candidate = candidates.entry(number).or_default();
        if compressed {
            candidate.1 = Some(entry.path());
        } else {
            candidate.0 = Some(entry.path());
        }
    }
    let mut segments = Vec::with_capacity(candidates.len());
    for (number, (raw, compressed)) in candidates {
        if let Some(path) = raw {
            segments.push(SegmentFile {
                number,
                path,
                compressed: false,
            });
        } else if let Some(path) = compressed {
            segments.push(SegmentFile {
                number,
                path,
                compressed: true,
            });
        }
    }
    for pair in segments.windows(2) {
        if pair[0].number.checked_add(1) != Some(pair[1].number) {
            return Err(JournalError::Format("segment sequence has a gap".to_owned()));
        }
    }
    for (index, segment) in segments.iter().enumerate() {
        if segment.compressed && index + 1 == segments.len() {
            continue;
        }
        if !segment.compressed && index + 1 != segments.len() && fs::metadata(&segment.path)?.len() == 0 {
            return Err(JournalError::Format("empty closed segment".to_owned()));
        }
    }
    Ok(segments)
}

fn first_available_event_id(segments: &[SegmentFile]) -> Result<Option<u64>, JournalError> {
    Ok(discover_first_event_ids(segments)?
        .into_iter()
        .flatten()
        .next())
}

fn discover_first_event_ids(segments: &[SegmentFile]) -> Result<Vec<Option<u64>>, JournalError> {
    segments
        .iter()
        .enumerate()
        .map(|(index, segment)| {
            let allow_tail = index + 1 == segments.len() && !segment.compressed;
            first_event_id(segment, allow_tail)
        })
        .collect()
}

fn first_event_id(segment: &SegmentFile, allow_tail: bool) -> Result<Option<u64>, JournalError> {
    if segment.compressed {
        return first_compressed_event_id(&segment.path);
    }
    let bytes = read_segment_bytes(segment)?;
    if bytes.is_empty() {
        return Ok(None);
    }
    let end = match item_end(&bytes, 0, 0) {
        Ok(end) => end,
        Err(ParseFailure::Incomplete) if allow_tail => return Ok(None),
        Err(error) => return Err(parse_error(error)),
    };
    Ok(Some(parse_record(&bytes[..end])?.event_id))
}

fn first_compressed_event_id(path: &Path) -> Result<Option<u64>, JournalError> {
    let file = File::open(path)?;
    let mut decoder = zstd::stream::read::Decoder::new(file)?;
    first_event_id_from_decoded_stream(&mut decoder)
}

fn first_event_id_from_decoded_stream(
    decoder: &mut impl Read,
) -> Result<Option<u64>, JournalError> {
    let mut bytes = Vec::new();
    let mut chunk = [0_u8; 4096];
    loop {
        match item_end(&bytes, 0, 0) {
            Ok(end) => return Ok(Some(parse_record(&bytes[..end])?.event_id)),
            Err(ParseFailure::Invalid(message)) => {
                return Err(JournalError::Format(message.to_owned()));
            }
            Err(ParseFailure::Incomplete) => {}
        }
        if bytes.len() > MAX_ENCODED_RECORD_LENGTH {
            return Err(JournalError::Format("CBOR journal record is too large".to_owned()));
        }
        let length = decoder.read(&mut chunk)?;
        if length == 0 {
            return if bytes.is_empty() {
                Ok(None)
            } else {
                Err(JournalError::Format(
                    "compressed segment contains an incomplete first CBOR item".to_owned(),
                ))
            };
        }
        bytes.extend_from_slice(&chunk[..length]);
    }
}

struct SegmentScan {
    events: Vec<JournalEvent>,
    ignored_crash_tail: bool,
    last_event_id: u64,
    last_boundary: u64,
}

fn scan_path(
    segment: &SegmentFile,
    requested_event_id: u64,
    allow_tail: bool,
    initial_event_id: u64,
) -> Result<SegmentScan, JournalError> {
    let bytes = read_segment_bytes(segment)?;
    scan_sequence(&bytes, requested_event_id, allow_tail, initial_event_id)
}

fn read_segment_bytes(segment: &SegmentFile) -> Result<Vec<u8>, JournalError> {
    if segment.compressed {
        return read_compressed_segment_bytes(&segment.path);
    }
    Ok(fs::read(&segment.path)?)
}

fn read_compressed_segment_bytes(path: &Path) -> Result<Vec<u8>, JournalError> {
    let file = File::open(path)?;
    let decoder = zstd::stream::read::Decoder::new(file)?;
    let mut bytes = Vec::new();
    decoder
        .take(MAX_DECOMPRESSED_SEGMENT_LENGTH + 1)
        .read_to_end(&mut bytes)?;
    if bytes.len() as u64 > MAX_DECOMPRESSED_SEGMENT_LENGTH {
        return Err(JournalError::Format(
            "decompressed journal segment exceeds the size limit".to_owned(),
        ));
    }
    Ok(bytes)
}

fn scan_sequence(
    bytes: &[u8],
    requested_event_id: u64,
    allow_tail: bool,
    initial_event_id: u64,
) -> Result<SegmentScan, JournalError> {
    let mut position = 0;
    let mut previous_event_id = initial_event_id;
    let mut events = Vec::new();
    let mut ignored_crash_tail = false;
    while position < bytes.len() {
        let end = match item_end(bytes, position, 0) {
            Ok(end) => end,
            Err(ParseFailure::Incomplete) if allow_tail => {
                ignored_crash_tail = true;
                break;
            }
            Err(error) => return Err(parse_error(error)),
        };
        if end - position > MAX_ENCODED_RECORD_LENGTH {
            return Err(JournalError::Format("CBOR journal record is too large".to_owned()));
        }
        let event = parse_record(&bytes[position..end])?;
        if event.event_id == 0 || event.event_id <= previous_event_id {
            return Err(JournalError::Format(
                "journal event IDs are not strictly increasing".to_owned(),
            ));
        }
        previous_event_id = event.event_id;
        if event.event_id > requested_event_id {
            events.push(event);
        }
        position = end;
    }
    Ok(SegmentScan {
        events,
        ignored_crash_tail,
        last_event_id: previous_event_id,
        last_boundary: position as u64,
    })
}

fn parse_record(encoded: &[u8]) -> Result<JournalEvent, JournalError> {
    let fields = array_fields(encoded)?;
    if fields.len() < 3 {
        return Err(JournalError::Format(
            "session event must contain at least three fields".to_owned(),
        ));
    }
    let event_id = decode_unsigned(&encoded[fields[0].0..fields[0].1], "eventId")?;
    let event_type_value = decode_unsigned(&encoded[fields[1].0..fields[1].1], "eventType")?;
    let event_type = u16::try_from(event_type_value)
        .map_err(|_| JournalError::Format("eventType exceeds u16".to_owned()))?;
    let encoded_payload = encoded[fields[2].0..fields[2].1].to_vec();
    let payload = decode_known_payload(event_type, &encoded_payload)?;
    Ok(JournalEvent {
        event_id,
        event_type,
        payload,
        encoded_payload,
        encoded_record: encoded.to_vec(),
        trailing_field_count: fields.len() - 3,
        opaque: !known_event_type(event_type),
    })
}

fn decode_known_payload(event_type: u16, encoded: &[u8]) -> Result<Vec<u8>, JournalError> {
    match event_type {
        protocol::event_type::PTY_OUTPUT => decode_bytes(encoded),
        protocol::event_type::PTY_INPUT => {
            let fields = array_fields(encoded)?;
            if fields.len() < 2 {
                return Err(JournalError::Format("PTY_INPUT payload has missing fields".to_owned()));
            }
            let command_id = decode_text(&encoded[fields[0].0..fields[0].1])?;
            let id = parse_uuid(&command_id)
                .ok_or_else(|| JournalError::Format("PTY_INPUT commandId is not a canonical UUID".to_owned()))?;
            let bytes = decode_bytes(&encoded[fields[1].0..fields[1].1])?;
            id.len()
                .checked_add(bytes.len())
                .filter(|length| *length <= MAX_PAYLOAD_LENGTH)
                .ok_or_else(|| JournalError::Format("PTY_INPUT payload exceeds the payload limit".to_owned()))?;
            Ok([id.to_vec(), bytes].concat())
        }
        protocol::event_type::PTY_RESIZE => {
            let fields = array_fields(encoded)?;
            if fields.len() < 2 {
                return Err(JournalError::Format("PTY_RESIZE payload has missing fields".to_owned()));
            }
            let cols = u32::try_from(decode_unsigned(
                &encoded[fields[0].0..fields[0].1],
                "PTY_RESIZE columns",
            )?)
            .map_err(|_| JournalError::Format("PTY_RESIZE columns exceed u32".to_owned()))?;
            let rows = u32::try_from(decode_unsigned(
                &encoded[fields[1].0..fields[1].1],
                "PTY_RESIZE rows",
            )?)
            .map_err(|_| JournalError::Format("PTY_RESIZE rows exceed u32".to_owned()))?;
            if !protocol::valid_terminal_dimensions(cols, rows) {
                return Err(JournalError::Format(
                    "PTY_RESIZE dimensions must be between 1 and 65535".to_owned(),
                ));
            }
            Ok(protocol::pty_resize_payload(cols, rows).to_vec())
        }
        protocol::event_type::PROCESS_STARTED => {
            let fields = array_fields(encoded)?;
            if fields.is_empty() {
                return Err(JournalError::Format("PROCESS_STARTED payload has missing fields".to_owned()));
            }
            let process_id = decode_unsigned(&encoded[fields[0].0..fields[0].1], "processId")?;
            if process_id == 0 {
                return Err(JournalError::Format("PROCESS_STARTED processId must be nonzero".to_owned()));
            }
            Ok(process_id.to_le_bytes().to_vec())
        }
        protocol::event_type::PROCESS_EXITED => {
            let fields = array_fields(encoded)?;
            if fields.is_empty() {
                return Err(JournalError::Format("PROCESS_EXITED payload has missing fields".to_owned()));
            }
            let exit_code = i32::try_from(decode_signed(&encoded[fields[0].0..fields[0].1])?)
                .map_err(|_| JournalError::Format("PROCESS_EXITED exit code exceeds i32".to_owned()))?;
            Ok(protocol::process_exited_payload(exit_code, -1).to_vec())
        }
        protocol::event_type::SIGNAL => {
            let fields = array_fields(encoded)?;
            if fields.len() < 2 {
                return Err(JournalError::Format("SIGNAL payload has missing fields".to_owned()));
            }
            let kind = u16::try_from(decode_unsigned(&encoded[fields[0].0..fields[0].1], "signal kind")?)
                .map_err(|_| JournalError::Format("signal kind exceeds u16".to_owned()))?;
            let code = i32::try_from(decode_signed(&encoded[fields[1].0..fields[1].1])?)
                .map_err(|_| JournalError::Format("signal code exceeds i32".to_owned()))?;
            if !protocol::valid_signal(kind, code) {
                return Err(JournalError::Format(
                    "SIGNAL kind and platform code are inconsistent".to_owned(),
                ));
            }
            let mut payload = Vec::with_capacity(8);
            payload.extend_from_slice(&kind.to_le_bytes());
            payload.extend_from_slice(&0_u16.to_le_bytes());
            payload.extend_from_slice(&code.to_le_bytes());
            Ok(payload)
        }
        _ => Ok(encoded.to_vec()),
    }
}

fn known_event_type(event_type: u16) -> bool {
    matches!(
        event_type,
        protocol::event_type::PTY_OUTPUT
            | protocol::event_type::PTY_INPUT
            | protocol::event_type::PTY_RESIZE
            | protocol::event_type::PROCESS_STARTED
            | protocol::event_type::PROCESS_EXITED
            | protocol::event_type::SIGNAL
    )
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum ParseFailure {
    Incomplete,
    Invalid(&'static str),
}

fn parse_error(error: ParseFailure) -> JournalError {
    match error {
        ParseFailure::Incomplete => JournalError::Format("incomplete CBOR item".to_owned()),
        ParseFailure::Invalid(message) => JournalError::Format(message.to_owned()),
    }
}

fn item_end(bytes: &[u8], start: usize, depth: usize) -> Result<usize, ParseFailure> {
    if depth > MAX_CBOR_DEPTH {
        return Err(ParseFailure::Invalid("CBOR nesting exceeds limit"));
    }
    let initial = *bytes.get(start).ok_or(ParseFailure::Incomplete)?;
    let major = initial >> 5;
    let additional = initial & 0x1f;
    if additional == 31 {
        return indefinite_end(bytes, start + 1, major, depth);
    }
    let (argument, mut position) = argument(bytes, start, additional)?;
    match major {
        0 | 1 | 7 => Ok(position),
        2 | 3 => {
            let length = usize::try_from(argument)
                .map_err(|_| ParseFailure::Invalid("CBOR string length exceeds platform"))?;
            let end = position
                .checked_add(length)
                .filter(|end| *end <= bytes.len())
                .ok_or(ParseFailure::Incomplete)?;
            if major == 3 && std::str::from_utf8(&bytes[position..end]).is_err() {
                return Err(ParseFailure::Invalid("CBOR text is not valid UTF-8"));
            }
            Ok(end)
        }
        4 => {
            for _ in 0..argument {
                position = item_end(bytes, position, depth + 1)?;
            }
            Ok(position)
        }
        5 => {
            let items = argument
                .checked_mul(2)
                .ok_or(ParseFailure::Invalid("CBOR map length overflows"))?;
            for _ in 0..items {
                position = item_end(bytes, position, depth + 1)?;
            }
            Ok(position)
        }
        6 => item_end(bytes, position, depth + 1),
        _ => Err(ParseFailure::Invalid("invalid CBOR major type")),
    }
}

fn indefinite_end(
    bytes: &[u8],
    mut position: usize,
    major: u8,
    depth: usize,
) -> Result<usize, ParseFailure> {
    if !matches!(major, 2 | 3 | 4 | 5) {
        return Err(ParseFailure::Invalid("invalid indefinite-length CBOR item"));
    }
    let mut map_items = 0_usize;
    loop {
        let next = *bytes.get(position).ok_or(ParseFailure::Incomplete)?;
        if next == 0xff {
            if major == 5 && map_items % 2 != 0 {
                return Err(ParseFailure::Invalid("indefinite CBOR map has an unmatched key"));
            }
            return Ok(position + 1);
        }
        if matches!(major, 2 | 3) {
            if next >> 5 != major || next & 0x1f == 31 {
                return Err(ParseFailure::Invalid("invalid CBOR indefinite string chunk"));
            }
        }
        position = item_end(bytes, position, depth + 1)?;
        map_items += 1;
    }
}

fn argument(bytes: &[u8], start: usize, additional: u8) -> Result<(u64, usize), ParseFailure> {
    match additional {
        value @ 0..=23 => Ok((u64::from(value), start + 1)),
        24 => Ok((u64::from(*bytes.get(start + 1).ok_or(ParseFailure::Incomplete)?), start + 2)),
        25 => {
            let end = start + 3;
            let value = bytes.get(start + 1..end).ok_or(ParseFailure::Incomplete)?;
            Ok((u64::from(u16::from_be_bytes(value.try_into().unwrap())), end))
        }
        26 => {
            let end = start + 5;
            let value = bytes.get(start + 1..end).ok_or(ParseFailure::Incomplete)?;
            Ok((u64::from(u32::from_be_bytes(value.try_into().unwrap())), end))
        }
        27 => {
            let end = start + 9;
            let value = bytes.get(start + 1..end).ok_or(ParseFailure::Incomplete)?;
            Ok((u64::from_be_bytes(value.try_into().unwrap()), end))
        }
        _ => Err(ParseFailure::Invalid("invalid CBOR additional information")),
    }
}

fn array_fields(encoded: &[u8]) -> Result<Vec<(usize, usize)>, JournalError> {
    let initial = *encoded
        .first()
        .ok_or_else(|| JournalError::Format("empty CBOR item".to_owned()))?;
    if initial >> 5 != 4 {
        return Err(JournalError::Format("session event field must be a CBOR array".to_owned()));
    }
    let additional = initial & 0x1f;
    let mut fields = Vec::new();
    let mut position;
    if additional == 31 {
        position = 1;
        while encoded.get(position) != Some(&0xff) {
            if fields.len() == MAX_RECORD_FIELDS {
                return Err(JournalError::Format("CBOR array has too many fields".to_owned()));
            }
            let end = item_end(encoded, position, 1).map_err(parse_error)?;
            fields.push((position, end));
            position = end;
        }
        position += 1;
    } else {
        let (length, content) = argument(encoded, 0, additional).map_err(parse_error)?;
        let length = usize::try_from(length)
            .map_err(|_| JournalError::Format("CBOR array length exceeds platform".to_owned()))?;
        if length > MAX_RECORD_FIELDS {
            return Err(JournalError::Format("CBOR array has too many fields".to_owned()));
        }
        position = content;
        for _ in 0..length {
            let end = item_end(encoded, position, 1).map_err(parse_error)?;
            fields.push((position, end));
            position = end;
        }
    }
    if position != encoded.len() {
        return Err(JournalError::Format("CBOR array has trailing bytes".to_owned()));
    }
    Ok(fields)
}

fn decode_unsigned(encoded: &[u8], name: &str) -> Result<u64, JournalError> {
    let initial = encoded
        .first()
        .ok_or_else(|| JournalError::Format(format!("{name} is empty")))?;
    if initial >> 5 != 0 || initial & 0x1f == 31 {
        return Err(JournalError::Format(format!("{name} must be an unsigned integer")));
    }
    let (value, end) = argument(encoded, 0, initial & 0x1f).map_err(parse_error)?;
    if end != encoded.len() {
        return Err(JournalError::Format(format!("{name} contains trailing bytes")));
    }
    Ok(value)
}

fn decode_signed(encoded: &[u8]) -> Result<i64, JournalError> {
    let initial = *encoded
        .first()
        .ok_or_else(|| JournalError::Format("signed integer is empty".to_owned()))?;
    let major = initial >> 5;
    if !matches!(major, 0 | 1) || initial & 0x1f == 31 {
        return Err(JournalError::Format("value must be a signed integer".to_owned()));
    }
    let (value, end) = argument(encoded, 0, initial & 0x1f).map_err(parse_error)?;
    if end != encoded.len() {
        return Err(JournalError::Format("signed integer contains trailing bytes".to_owned()));
    }
    match major {
        0 => i64::try_from(value)
            .map_err(|_| JournalError::Format("signed integer exceeds i64".to_owned())),
        1 => {
            let magnitude = i128::from(value);
            i64::try_from(-1_i128 - magnitude)
                .map_err(|_| JournalError::Format("signed integer is below i64".to_owned()))
        }
        _ => unreachable!(),
    }
}

fn decode_bytes(encoded: &[u8]) -> Result<Vec<u8>, JournalError> {
    decode_string(encoded, 2)
}

fn decode_text(encoded: &[u8]) -> Result<String, JournalError> {
    let bytes = decode_string(encoded, 3)?;
    String::from_utf8(bytes)
        .map_err(|_| JournalError::Format("CBOR text is not valid UTF-8".to_owned()))
}

fn decode_string(encoded: &[u8], expected_major: u8) -> Result<Vec<u8>, JournalError> {
    let initial = *encoded
        .first()
        .ok_or_else(|| JournalError::Format("CBOR string is empty".to_owned()))?;
    if initial >> 5 != expected_major {
        return Err(JournalError::Format("CBOR value has the wrong string type".to_owned()));
    }
    if initial & 0x1f == 31 {
        return decode_indefinite_string(encoded, expected_major);
    }
    let (length, content) = argument(encoded, 0, initial & 0x1f).map_err(parse_error)?;
    let length = usize::try_from(length)
        .map_err(|_| JournalError::Format("CBOR string length exceeds platform".to_owned()))?;
    if length > MAX_PAYLOAD_LENGTH {
        return Err(JournalError::Format("CBOR string exceeds the payload limit".to_owned()));
    }
    let end = content
        .checked_add(length)
        .ok_or_else(|| JournalError::Format("CBOR string length overflows".to_owned()))?;
    if end != encoded.len() {
        return Err(JournalError::Format("CBOR string length does not match item".to_owned()));
    }
    Ok(encoded[content..end].to_vec())
}

fn decode_indefinite_string(encoded: &[u8], expected_major: u8) -> Result<Vec<u8>, JournalError> {
    let mut decoded = Vec::new();
    let mut position = 1;
    loop {
        let initial = *encoded
            .get(position)
            .ok_or_else(|| JournalError::Format("CBOR indefinite string has no break".to_owned()))?;
        if initial == 0xff {
            position += 1;
            if position != encoded.len() {
                return Err(JournalError::Format(
                    "CBOR indefinite string has trailing bytes".to_owned(),
                ));
            }
            return Ok(decoded);
        }
        if initial >> 5 != expected_major || initial & 0x1f == 31 {
            return Err(JournalError::Format("invalid CBOR indefinite string chunk".to_owned()));
        }
        let (length, content) = argument(encoded, position, initial & 0x1f).map_err(parse_error)?;
        let length = usize::try_from(length)
            .map_err(|_| JournalError::Format("CBOR string length exceeds platform".to_owned()))?;
        let end = content
            .checked_add(length)
            .filter(|end| *end <= encoded.len())
            .ok_or_else(|| JournalError::Format("CBOR string chunk is incomplete".to_owned()))?;
        let chunk = &encoded[content..end];
        if expected_major == 3 && std::str::from_utf8(chunk).is_err() {
            return Err(JournalError::Format("CBOR text chunk is not valid UTF-8".to_owned()));
        }
        let new_length = decoded
            .len()
            .checked_add(chunk.len())
            .filter(|length| *length <= MAX_PAYLOAD_LENGTH)
            .ok_or_else(|| JournalError::Format("CBOR string exceeds the payload limit".to_owned()))?;
        decoded.reserve(new_length - decoded.len());
        decoded.extend_from_slice(chunk);
        position = end;
    }
}

fn segment_path(directory: &Path, number: u64) -> PathBuf {
    directory.join(format!("{number:08}.cbor"))
}

fn compressed_segment_path(directory: &Path, number: u64) -> PathBuf {
    directory.join(format!("{number:08}.cbor.zst"))
}

fn compressed_temporary_segment_path(directory: &Path, number: u64) -> PathBuf {
    directory.join(format!("{number:08}.cbor.zst.tmp"))
}

fn segment_number(name: &std::ffi::OsStr) -> Option<(u64, bool)> {
    let name = name.to_str()?;
    let (number, compressed) = if let Some(number) = name.strip_suffix(".cbor.zst") {
        (number, true)
    } else {
        (name.strip_suffix(".cbor")?, false)
    };
    if number.len() != 8 || !number.bytes().all(|byte| byte.is_ascii_digit()) {
        return None;
    }
    let number = number.parse().ok()?;
    (number != 0).then_some((number, compressed))
}

fn format_uuid(bytes: [u8; 16]) -> String {
    format!(
        "{:02x}{:02x}{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}{:02x}{:02x}{:02x}{:02x}",
        bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
        bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15],
    )
}

fn parse_uuid(value: &str) -> Option<[u8; 16]> {
    let mut compact = String::with_capacity(32);
    for (index, byte) in value.bytes().enumerate() {
        if matches!(index, 8 | 13 | 18 | 23) {
            if byte != b'-' {
                return None;
            }
        } else {
            if !byte.is_ascii_digit() && !matches!(byte, b'a'..=b'f') {
                return None;
            }
            compact.push(char::from(byte));
        }
    }
    if compact.len() != 32 {
        return None;
    }
    let mut result = [0_u8; 16];
    for (index, byte) in result.iter_mut().enumerate() {
        *byte = u8::from_str_radix(&compact[index * 2..index * 2 + 2], 16).ok()?;
    }
    Some(result)
}

fn epoch_millis() -> Result<u64, JournalError> {
    let elapsed = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|error| JournalError::Format(format!("system clock before epoch: {error}")))?;
    Ok(u64::try_from(elapsed.as_millis()).unwrap_or(u64::MAX))
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Metadata {
    pub metadata_version: u16,
    pub journal_format_version: u16,
    pub control_protocol_version: u16,
    pub session_id: String,
    pub created_at_epoch_millis: u64,
    pub session_start_epoch_millis: u64,
    pub command: Vec<String>,
    pub cwd: String,
    pub host_pid: u64,
    pub child_pid: Option<u64>,
    pub initial_cols: u16,
    pub initial_rows: u16,
    pub current_cols: u16,
    pub current_rows: u16,
    pub term: String,
    pub sandbox: SandboxMetadata,
    pub control: ControlMetadata,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SandboxMetadata {
    pub requested: bool,
    pub enforcement: SandboxEnforcement,
    pub unavailable_policy: SandboxUnavailablePolicy,
    pub read_write_paths: Vec<String>,
    pub read_only_paths: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub policy_version: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub handled_rights: Option<u64>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub rules: Vec<SandboxRuleMetadata>,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SandboxRuleMetadata {
    pub path: String,
    pub rights: Vec<String>,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum SandboxEnforcement {
    None,
    Landlock,
    Future,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "kebab-case")]
pub enum SandboxUnavailablePolicy {
    Fail,
    RunUnsandboxed,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ControlMetadata {
    pub transport: ControlTransport,
    pub endpoint: String,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "kebab-case")]
pub enum ControlTransport {
    UnixDomainSocket,
    NamedPipe,
}

pub fn read_metadata(directory: impl AsRef<Path>) -> Result<Metadata, JournalError> {
    let bytes = fs::read(directory.as_ref().join(METADATA_NAME))?;
    let metadata = serde_json::from_slice(&bytes)
        .map_err(|error| JournalError::Format(format!("invalid metadata JSON: {error}")))?;
    validate_metadata(&metadata)?;
    Ok(metadata)
}

pub fn write_metadata(
    directory: impl AsRef<Path>,
    metadata: &Metadata,
    durability: Durability,
) -> Result<(), JournalError> {
    validate_metadata(metadata)?;
    let directory = directory.as_ref();
    fs::create_dir_all(directory)?;
    let temporary = directory.join(format!(
        ".{METADATA_NAME}.tmp-{}-{}",
        std::process::id(),
        epoch_millis()?
    ));
    let contents = serde_json::to_vec_pretty(metadata)
        .map_err(|error| JournalError::Format(format!("cannot encode metadata JSON: {error}")))?;
    let write_result = (|| -> Result<(), JournalError> {
        let mut file = OpenOptions::new().write(true).create_new(true).open(&temporary)?;
        file.write_all(&contents)?;
        file.write_all(b"\n")?;
        if durability == Durability::EveryRecord {
            file.sync_all()?;
        }
        drop(file);
        fs::rename(&temporary, directory.join(METADATA_NAME))?;
        if durability == Durability::EveryRecord {
            sync_directory(directory)?;
        }
        Ok(())
    })();
    if write_result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    write_result
}

#[cfg(unix)]
fn sync_directory(directory: &Path) -> Result<(), JournalError> {
    File::open(directory)?.sync_all()?;
    Ok(())
}

#[cfg(windows)]
fn sync_directory(directory: &Path) -> Result<(), JournalError> {
    use std::os::windows::fs::OpenOptionsExt;

    const FILE_FLAG_BACKUP_SEMANTICS: u32 = 0x0200_0000;
    OpenOptions::new()
        .read(true)
        .write(true)
        .custom_flags(FILE_FLAG_BACKUP_SEMANTICS)
        .open(directory)?
        .sync_all()?;
    Ok(())
}

fn validate_metadata(metadata: &Metadata) -> Result<(), JournalError> {
    if metadata.metadata_version != 1
        || metadata.journal_format_version != protocol::JOURNAL_VERSION
        || metadata.control_protocol_version != protocol::CONTROL_VERSION
        || !valid_session_id(&metadata.session_id)
        || metadata.command.is_empty()
        || metadata.cwd.is_empty()
        || metadata.host_pid == 0
        || metadata.child_pid == Some(0)
        || !valid_dimensions(metadata.initial_cols, metadata.initial_rows)
        || !valid_dimensions(metadata.current_cols, metadata.current_rows)
        || metadata.term.is_empty()
        || metadata.term.len() > 128
        || metadata.control.endpoint.is_empty()
    {
        return Err(JournalError::Format("metadata has invalid required fields".to_owned()));
    }
    Ok(())
}

fn valid_session_id(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 128
        && !matches!(value, "." | "..")
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
}

fn valid_dimensions(cols: u16, rows: u16) -> bool {
    cols != 0 && rows != 0
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::OsStr;
    use std::sync::{Arc, Condvar, Mutex};
    use std::time::Duration;

    #[derive(Clone, Copy, Debug, Eq, PartialEq)]
    enum RetentionFailureSignal {
        AttemptStarted,
        FailureReturned,
        RetryStarted,
    }

    struct FailingRetentionFileSystem {
        target_name: &'static str,
        remaining_failures: Mutex<Option<usize>>,
        signal: Sender<RetentionFailureSignal>,
        release_first_failure: Option<Arc<(Mutex<bool>, Condvar)>>,
        release_retry: Option<Arc<(Mutex<bool>, Condvar)>>,
    }

    struct ReleaseMaintenanceWaits {
        waits: [Arc<(Mutex<bool>, Condvar)>; 2],
    }

    impl Drop for ReleaseMaintenanceWaits {
        fn drop(&mut self) {
            for wait in &self.waits {
                let (lock, condition) = &**wait;
                let mut released = match lock.lock() {
                    Ok(released) => released,
                    Err(poisoned) => poisoned.into_inner(),
                };
                *released = true;
                condition.notify_all();
            }
        }
    }

    fn receive_retention_signal(
        receiver: &Receiver<RetentionFailureSignal>,
    ) -> RetentionFailureSignal {
        receiver
            .recv_timeout(Duration::from_secs(5))
            .unwrap_or_else(|error| panic!("journal retention maintenance signal failed: {error}"))
    }

    impl MaintenanceFileSystem for FailingRetentionFileSystem {
        fn rename(&self, source: &Path, target: &Path) -> io::Result<()> {
            fs::rename(source, target)
        }

        fn remove_file(&self, path: &Path) -> io::Result<()> {
            let is_target = path.file_name() == Some(OsStr::new(self.target_name));
            let should_fail = if is_target {
                let mut remaining = self.remaining_failures.lock().unwrap();
                match remaining.as_mut() {
                    Some(0) => false,
                    Some(count) => {
                        *count -= 1;
                        true
                    }
                    None => true,
                }
            } else {
                false
            };
            if !should_fail {
                if is_target {
                    if let Some(release) = &self.release_retry {
                        let _ = self.signal.send(RetentionFailureSignal::RetryStarted);
                        let (lock, condition) = &**release;
                        let mut released = lock.lock().unwrap();
                        while !*released {
                            released = condition.wait(released).unwrap();
                        }
                    }
                }
                return fs::remove_file(path);
            }
            let _ = self
                .signal
                .send(RetentionFailureSignal::AttemptStarted);
            if let Some(release) = &self.release_first_failure {
                let (lock, condition) = &**release;
                let mut released = lock.lock().unwrap();
                while !*released {
                    released = condition.wait(released).unwrap();
                }
                let _ = self
                    .signal
                    .send(RetentionFailureSignal::FailureReturned);
            }
            Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "injected retention deletion failure",
            ))
        }
    }

    fn temporary_directory(name: &str) -> PathBuf {
        let directory = std::env::temp_dir().join(format!(
            "orion-session-host-{name}-{}-{}",
            std::process::id(),
            epoch_millis().unwrap()
        ));
        fs::create_dir(&directory).unwrap();
        directory
    }

    fn hex_bytes(value: &str) -> Vec<u8> {
        value
            .bytes()
            .filter(|byte| !byte.is_ascii_whitespace())
            .collect::<Vec<_>>()
            .chunks_exact(2)
            .map(|pair| {
                let text = std::str::from_utf8(pair).unwrap();
                u8::from_str_radix(text, 16).unwrap()
            })
            .collect()
    }

    fn definite_string(major: u8, bytes: &[u8]) -> Vec<u8> {
        let mut encoded = vec![(major << 5) | 26];
        encoded.extend_from_slice(&u32::try_from(bytes.len()).unwrap().to_be_bytes());
        encoded.extend_from_slice(bytes);
        encoded
    }

    fn journal_config(segment_max_bytes: u64, journal_max_bytes: u64) -> JournalConfig {
        JournalConfig {
            durability: Durability::Buffered,
            segment_max_bytes,
            journal_max_bytes,
        }
    }

    fn decode_compressed_segment(directory: &Path, number: u64) -> Vec<u8> {
        let compressed = fs::read(directory.join(format!("{number:08}.cbor.zst"))).unwrap();
        zstd::stream::decode_all(compressed.as_slice()).unwrap()
    }

    fn write_raw_event(directory: &Path, number: u64, event_id: u64, payload: &[u8]) -> Vec<u8> {
        let encoded = encode_event(event_id, protocol::event_type::PTY_OUTPUT, payload).unwrap();
        fs::write(segment_path(directory, number), &encoded).unwrap();
        encoded
    }

    fn write_compressed_bytes(directory: &Path, number: u64, decoded: &[u8]) -> Vec<u8> {
        let compressed = zstd::stream::encode_all(decoded, 1).unwrap();
        fs::write(compressed_segment_path(directory, number), &compressed).unwrap();
        compressed
    }

    #[test]
    fn retention_deletes_the_oldest_closed_prefix_to_reach_the_limit() {
        let segments = [
            SegmentSize::closed(1, 40),
            SegmentSize::closed(2, 40),
            SegmentSize::active(3, 40),
        ];

        assert_eq!(retention_deletions(&segments, 80).unwrap(), [1]);
    }

    #[test]
    fn retention_deletes_every_closed_segment_when_the_active_segment_exceeds_the_limit() {
        let segments = [
            SegmentSize::closed(1, 40),
            SegmentSize::closed(2, 40),
            SegmentSize::active(3, 40),
        ];

        assert_eq!(retention_deletions(&segments, 20).unwrap(), [1, 2]);
    }

    #[test]
    fn retention_keeps_every_segment_when_the_journal_is_within_the_limit() {
        let segments = [
            SegmentSize::closed(1, 40),
            SegmentSize::closed(2, 40),
            SegmentSize::active(3, 40),
        ];

        assert!(retention_deletions(&segments, 120).unwrap().is_empty());
    }

    #[test]
    fn retention_reports_physical_size_overflow() {
        let segments = [
            SegmentSize::closed(1, u64::MAX),
            SegmentSize::active(2, 1),
        ];

        assert!(matches!(
            retention_deletions(&segments, u64::MAX),
            Err(JournalError::Maintenance(message))
                if message == "physical journal size overflow"
        ));
    }

    #[test]
    fn journal_config_defaults_to_buffered_approved_size_limits() {
        let config = JournalConfig::default();

        assert_eq!(config.durability, Durability::Buffered);
        assert_eq!(config.segment_max_bytes, 64 * 1024 * 1024);
        assert_eq!(config.journal_max_bytes, 1024 * 1024 * 1024);
    }

    #[test]
    fn rotates_before_an_item_would_cross_the_segment_limit() {
        let directory = temporary_directory("automatic-rotation");
        let first = encode_event(1, protocol::event_type::PTY_OUTPUT, b"one").unwrap();
        let second = encode_event(2, protocol::event_type::PTY_OUTPUT, b"two").unwrap();
        let limit = u64::try_from(first.len() + second.len() - 1).unwrap();
        let mut writer =
            JournalWriter::create(&directory, [7; 16], journal_config(limit, 1024)).unwrap();

        writer
            .append_at(1, protocol::event_type::PTY_OUTPUT, 1, 0, b"one")
            .unwrap();
        writer
            .append_at(2, protocol::event_type::PTY_OUTPUT, 1, 0, b"two")
            .unwrap();
        writer.flush().unwrap();
        writer.finish_maintenance().unwrap();

        assert_eq!(decode_compressed_segment(&directory, 1), first);
        assert_eq!(fs::read(directory.join("00000002.cbor")).unwrap(), second);
        assert_eq!(writer.active_segment_number(), 2);
        assert_eq!(
            read_after(&directory, 0)
                .unwrap()
                .events
                .iter()
                .map(|event| event.event_id)
                .collect::<Vec<_>>(),
            [1, 2],
        );
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn compresses_every_closed_segment_and_leaves_the_active_segment_raw() {
        let directory = temporary_directory("background-compression");
        let encoded = encode_event(1, protocol::event_type::PTY_OUTPUT, b"same-size").unwrap();
        let limit = u64::try_from(encoded.len()).unwrap();
        let mut writer =
            JournalWriter::create(&directory, [7; 16], journal_config(limit, 1024)).unwrap();

        for event_id in 1..=3 {
            writer
                .append_at(event_id, protocol::event_type::PTY_OUTPUT, 1, 0, b"same-size")
                .unwrap();
        }
        writer.finish_maintenance().unwrap();
        writer.finish_maintenance().unwrap();

        for number in 1..=2 {
            assert!(directory.join(format!("{number:08}.cbor.zst")).is_file());
            assert!(!directory.join(format!("{number:08}.cbor")).exists());
        }
        assert!(directory.join("00000003.cbor").is_file());
        assert!(!directory.join("00000003.cbor.zst").exists());
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn rejects_append_after_maintenance_has_finished_without_mutating_the_writer() {
        let directory = temporary_directory("append-after-maintenance");
        let encoded = encode_event(1, protocol::event_type::PTY_OUTPUT, b"same-size").unwrap();
        let limit = u64::try_from(encoded.len()).unwrap();
        let mut writer =
            JournalWriter::create(&directory, [7; 16], journal_config(limit, 1024)).unwrap();
        writer
            .append_at(1, protocol::event_type::PTY_OUTPUT, 1, 0, b"same-size")
            .unwrap();
        writer.finish_maintenance().unwrap();

        let error = writer
            .append_at(2, protocol::event_type::PTY_OUTPUT, 1, 0, b"same-size")
            .unwrap_err();

        assert!(matches!(
            error,
            JournalError::Maintenance(message) if message == "maintenance has already finished"
        ));
        assert_eq!(writer.active_segment_number(), 1);
        assert_eq!(writer.latest_event_id(), Some(1));
        assert!(!directory.join("00000002.cbor").exists());
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn rejects_direct_rotation_after_maintenance_has_finished() {
        let directory = temporary_directory("rotate-after-maintenance");
        let mut writer =
            JournalWriter::create(&directory, [7; 16], JournalConfig::default()).unwrap();
        writer.finish_maintenance().unwrap();

        let error = writer.rotate().unwrap_err();

        assert!(matches!(
            error,
            JournalError::Maintenance(message) if message == "maintenance has already finished"
        ));
        assert_eq!(writer.active_segment_number(), 1);
        assert!(!directory.join("00000002.cbor").exists());
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn compressed_segments_decode_to_the_exact_original_cbor_bytes() {
        let directory = temporary_directory("exact-compressed-bytes");
        let payloads = [b"aaaaaaaa".as_slice(), b"bbbbbbbb".as_slice(), b"cccccccc".as_slice()];
        let expected = payloads
            .iter()
            .enumerate()
            .map(|(index, payload)| {
                encode_event(
                    u64::try_from(index + 1).unwrap(),
                    protocol::event_type::PTY_OUTPUT,
                    payload,
                )
                .unwrap()
            })
            .collect::<Vec<_>>();
        let limit = u64::try_from(expected[0].len()).unwrap();
        let mut writer =
            JournalWriter::create(&directory, [7; 16], journal_config(limit, 1024)).unwrap();

        for (index, payload) in payloads.iter().enumerate() {
            writer
                .append_at(
                    u64::try_from(index + 1).unwrap(),
                    protocol::event_type::PTY_OUTPUT,
                    1,
                    0,
                    payload,
                )
                .unwrap();
        }
        writer.finish_maintenance().unwrap();

        assert_eq!(decode_compressed_segment(&directory, 1), expected[0]);
        assert_eq!(decode_compressed_segment(&directory, 2), expected[1]);
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn compression_preserves_compressible_and_incompressible_event_sequences() {
        let mut random_payload = vec![0_u8; 4096];
        let mut state = 0x91a2_b3c4_u32;
        for byte in &mut random_payload {
            state ^= state << 13;
            state ^= state >> 17;
            state ^= state << 5;
            *byte = state.to_le_bytes()[0];
        }
        let payload_sets = [
            ("compressible", vec![0x5a; 4096]),
            ("incompressible", random_payload),
        ];

        for (name, payload) in payload_sets {
            let directory = temporary_directory(name);
            let encoded = encode_event(1, protocol::event_type::PTY_OUTPUT, &payload).unwrap();
            let limit = u64::try_from(encoded.len()).unwrap();
            let mut writer =
                JournalWriter::create(&directory, [7; 16], journal_config(limit, 64 * 1024)).unwrap();

            for event_id in 1..=4 {
                writer
                    .append_at(event_id, protocol::event_type::PTY_OUTPUT, 1, 0, &payload)
                    .unwrap();
            }
            writer.finish_maintenance().unwrap();

            let read = read_after(&directory, 0).unwrap();
            assert_eq!(
                read.events.iter().map(|event| event.event_id).collect::<Vec<_>>(),
                [1, 2, 3, 4],
            );
            assert!(read.events.iter().all(|event| event.payload == payload));
            drop(writer);
            fs::remove_dir_all(directory).unwrap();
        }
    }

    #[test]
    fn retention_uses_physical_sizes_and_preserves_a_readable_contiguous_suffix() {
        let directory = temporary_directory("physical-retention");
        let mut payload = vec![0_u8; 2048];
        let mut state = 0x6d2b_79f5_u32;
        for byte in &mut payload {
            state ^= state << 13;
            state ^= state >> 17;
            state ^= state << 5;
            *byte = state.to_le_bytes()[0];
        }
        let records = (1..=5)
            .map(|event_id| {
                encode_event(event_id, protocol::event_type::PTY_OUTPUT, &payload).unwrap()
            })
            .collect::<Vec<_>>();
        let compressed_sizes = records
            .iter()
            .map(|record| zstd::stream::encode_all(record.as_slice(), 3).unwrap().len() as u64)
            .collect::<Vec<_>>();
        let segment_max_bytes = records[0].len() as u64;
        let journal_max_bytes = compressed_sizes[3] + records[4].len() as u64;
        let mut writer = JournalWriter::create(
            &directory,
            [7; 16],
            journal_config(segment_max_bytes, journal_max_bytes),
        )
        .unwrap();

        for event_id in 1..=5 {
            writer
                .append_at(event_id, protocol::event_type::PTY_OUTPUT, 1, 0, &payload)
                .unwrap();
        }
        writer.finish_maintenance().unwrap();

        for number in 1..=3 {
            assert!(!segment_path(&directory, number).exists());
            assert!(!compressed_segment_path(&directory, number).exists());
        }
        let segments = discover_segments(&directory).unwrap();
        assert_eq!(
            segments.iter().map(|segment| segment.number).collect::<Vec<_>>(),
            [4, 5]
        );
        assert!(segments[0].compressed);
        assert!(!segments[1].compressed);
        assert!(!segment_path(&directory, 4).exists());
        assert!(!compressed_segment_path(&directory, 5).exists());
        let physical_total = segments
            .iter()
            .map(|segment| fs::metadata(&segment.path).unwrap().len())
            .sum::<u64>();
        assert!(physical_total <= journal_max_bytes);
        let read = read_after(&directory, 0).unwrap();
        assert_eq!(
            read.gap,
            Some(RetentionGap {
                requested_event_id: 0,
                first_available_event_id: 4,
            })
        );
        assert_eq!(
            read.events.iter().map(|event| event.event_id).collect::<Vec<_>>(),
            [4, 5]
        );
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn retention_never_deletes_an_oversized_active_segment() {
        let directory = temporary_directory("oversized-active-retention");
        let payload = vec![0x5a; 4096];
        let mut writer =
            JournalWriter::create(&directory, [7; 16], journal_config(1, 1)).unwrap();

        writer
            .append_at(1, protocol::event_type::PTY_OUTPUT, 1, 0, &payload)
            .unwrap();
        writer.finish_maintenance().unwrap();

        let active = segment_path(&directory, 1);
        assert!(active.is_file());
        assert!(fs::metadata(active).unwrap().len() > 1);
        let read = read_after(&directory, 0).unwrap();
        assert_eq!(read.events.len(), 1);
        assert_eq!(read.events[0].event_id, 1);
        assert_eq!(read.events[0].payload, payload);
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn maintenance_retries_a_transient_retention_deletion_without_blocking_writes() {
        let directory = temporary_directory("retention-deletion-retry");
        write_raw_event(&directory, 1, 1, b"closed");
        write_raw_event(&directory, 2, 2, b"active");
        let (signal_sender, signal_receiver) = mpsc::channel();
        let release = Arc::new((Mutex::new(false), Condvar::new()));
        let release_retry = Arc::new((Mutex::new(false), Condvar::new()));
        let file_system = Arc::new(FailingRetentionFileSystem {
            target_name: "00000001.cbor.zst",
            remaining_failures: Mutex::new(Some(1)),
            signal: signal_sender,
            release_first_failure: Some(release.clone()),
            release_retry: Some(release_retry.clone()),
        });
        let mut writer = JournalWriter::recover_with_maintenance_file_system(
            &directory,
            [7; 16],
            journal_config(1, 1),
            file_system,
        )
        .unwrap();
        let _release_guard = ReleaseMaintenanceWaits {
            waits: [release.clone(), release_retry.clone()],
        };

        assert_eq!(
            receive_retention_signal(&signal_receiver),
            RetentionFailureSignal::AttemptStarted
        );
        writer
            .append_at(3, protocol::event_type::PTY_OUTPUT, 1, 0, b"new active")
            .unwrap();
        assert!(compressed_segment_path(&directory, 1).is_file());
        assert!(!segment_path(&directory, 1).exists());
        let (lock, condition) = &*release;
        *lock.lock().unwrap() = true;
        condition.notify_one();
        assert_eq!(
            receive_retention_signal(&signal_receiver),
            RetentionFailureSignal::FailureReturned
        );
        assert_eq!(
            receive_retention_signal(&signal_receiver),
            RetentionFailureSignal::RetryStarted
        );
        assert!(compressed_segment_path(&directory, 1).is_file());
        let (lock, condition) = &*release_retry;
        *lock.lock().unwrap() = true;
        condition.notify_one();

        writer.finish_maintenance().unwrap();

        assert!(!compressed_segment_path(&directory, 1).exists());
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn finish_surfaces_a_persistent_retention_deletion_failure_after_writes_succeed() {
        let directory = temporary_directory("persistent-retention-deletion-failure");
        write_raw_event(&directory, 1, 1, b"closed");
        write_raw_event(&directory, 2, 2, b"active");
        let (signal_sender, signal_receiver) = mpsc::channel();
        let file_system = Arc::new(FailingRetentionFileSystem {
            target_name: "00000001.cbor.zst",
            remaining_failures: Mutex::new(None),
            signal: signal_sender,
            release_first_failure: None,
            release_retry: None,
        });
        let mut writer = JournalWriter::recover_with_maintenance_file_system(
            &directory,
            [7; 16],
            journal_config(1, 1),
            file_system,
        )
        .unwrap();

        assert_eq!(
            receive_retention_signal(&signal_receiver),
            RetentionFailureSignal::AttemptStarted
        );
        writer
            .append_at(3, protocol::event_type::PTY_OUTPUT, 1, 0, b"new active")
            .unwrap();
        let error = writer.finish_maintenance().unwrap_err();

        assert!(matches!(
            error,
            JournalError::Io(error) if error.kind() == io::ErrorKind::PermissionDenied
        ));
        assert!(compressed_segment_path(&directory, 1).is_file());
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn recovery_schedules_compression_of_existing_closed_raw_segments() {
        let directory = temporary_directory("recovery-compression");
        for event_id in 1..=3 {
            let encoded = encode_event(event_id, protocol::event_type::PTY_OUTPUT, b"payload").unwrap();
            fs::write(directory.join(format!("{event_id:08}.cbor")), encoded).unwrap();
        }

        let mut writer =
            JournalWriter::recover(&directory, [7; 16], journal_config(1024, 4096)).unwrap();
        writer.finish_maintenance().unwrap();

        assert!(directory.join("00000001.cbor.zst").is_file());
        assert!(directory.join("00000002.cbor.zst").is_file());
        assert!(directory.join("00000003.cbor").is_file());
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn recovery_replaces_an_abandoned_compression_temporary_file() {
        let directory = temporary_directory("recovery-abandoned-compression");
        let closed = write_raw_event(&directory, 1, 1, b"closed");
        let active = write_raw_event(&directory, 2, 2, b"active");
        fs::write(compressed_temporary_segment_path(&directory, 1), b"abandoned").unwrap();
        fs::write(compressed_temporary_segment_path(&directory, 99), b"temporary-only").unwrap();

        let discovered = discover_segments(&directory).unwrap();
        assert_eq!(discovered.iter().map(|segment| segment.number).collect::<Vec<_>>(), [1, 2]);
        let before = read_after(&directory, 0).unwrap();
        assert_eq!(before.events.iter().map(|event| event.event_id).collect::<Vec<_>>(), [1, 2]);

        let mut writer =
            JournalWriter::recover(&directory, [7; 16], journal_config(1024, 4096)).unwrap();
        writer.finish_maintenance().unwrap();

        assert!(!segment_path(&directory, 1).exists());
        assert!(!compressed_temporary_segment_path(&directory, 1).exists());
        assert!(compressed_temporary_segment_path(&directory, 99).is_file());
        assert_eq!(decode_compressed_segment(&directory, 1), closed);
        assert_eq!(fs::read(segment_path(&directory, 2)).unwrap(), active);
        assert!(!compressed_segment_path(&directory, 2).exists());
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn recovery_keeps_an_equal_published_compression_and_removes_the_raw_copy() {
        let directory = temporary_directory("recovery-equal-compression");
        let closed = write_raw_event(&directory, 1, 1, b"closed");
        let published = write_compressed_bytes(&directory, 1, &closed);
        write_raw_event(&directory, 2, 2, b"active");

        let discovered = discover_segments(&directory).unwrap();
        assert_eq!(discovered[0].path, segment_path(&directory, 1));
        assert!(!discovered[0].compressed);
        let before = read_after(&directory, 0).unwrap();
        assert_eq!(before.events.iter().map(|event| event.event_id).collect::<Vec<_>>(), [1, 2]);

        let mut writer =
            JournalWriter::recover(&directory, [7; 16], journal_config(1024, 4096)).unwrap();
        writer.finish_maintenance().unwrap();

        assert_eq!(fs::read(compressed_segment_path(&directory, 1)).unwrap(), published);
        assert!(!segment_path(&directory, 1).exists());
        assert!(segment_path(&directory, 2).is_file());
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn recovery_rebuilds_a_corrupt_published_compression_from_the_raw_copy() {
        let directory = temporary_directory("recovery-corrupt-compression");
        let closed = write_raw_event(&directory, 1, 1, b"closed");
        fs::write(compressed_segment_path(&directory, 1), b"not-zstd").unwrap();
        write_raw_event(&directory, 2, 2, b"active");

        let before = read_after(&directory, 0).unwrap();
        assert_eq!(before.events.iter().map(|event| event.event_id).collect::<Vec<_>>(), [1, 2]);

        let mut writer =
            JournalWriter::recover(&directory, [7; 16], journal_config(1024, 4096)).unwrap();
        writer.finish_maintenance().unwrap();

        assert_eq!(decode_compressed_segment(&directory, 1), closed);
        assert!(!segment_path(&directory, 1).exists());
        assert!(segment_path(&directory, 2).is_file());
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn recovery_rebuilds_a_different_published_compression_from_the_raw_copy() {
        let directory = temporary_directory("recovery-different-compression");
        let closed = write_raw_event(&directory, 1, 1, b"authoritative");
        let different = encode_event(1, protocol::event_type::PTY_OUTPUT, b"different").unwrap();
        assert_ne!(different, closed);
        write_compressed_bytes(&directory, 1, &different);
        write_raw_event(&directory, 2, 2, b"active");

        let before = read_after(&directory, 0).unwrap();
        assert_eq!(before.events[0].payload, b"authoritative");
        assert_eq!(before.events.iter().map(|event| event.event_id).collect::<Vec<_>>(), [1, 2]);

        let mut writer =
            JournalWriter::recover(&directory, [7; 16], journal_config(1024, 4096)).unwrap();
        writer.finish_maintenance().unwrap();

        assert_eq!(decode_compressed_segment(&directory, 1), closed);
        assert!(!segment_path(&directory, 1).exists());
        assert!(segment_path(&directory, 2).is_file());
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn reconciliation_preserves_one_ordered_event_per_logical_segment() {
        let directory = temporary_directory("recovery-logical-segments");
        let first = write_raw_event(&directory, 1, 1, b"first");
        let stale = encode_event(1, protocol::event_type::PTY_OUTPUT, b"stale").unwrap();
        write_compressed_bytes(&directory, 1, &stale);
        let second = write_raw_event(&directory, 2, 2, b"second");
        fs::write(compressed_temporary_segment_path(&directory, 2), b"abandoned").unwrap();
        let third = write_raw_event(&directory, 3, 3, b"third");

        let mut writer =
            JournalWriter::recover(&directory, [7; 16], journal_config(1024, 4096)).unwrap();
        writer.finish_maintenance().unwrap();

        assert_eq!(decode_compressed_segment(&directory, 1), first);
        assert_eq!(decode_compressed_segment(&directory, 2), second);
        assert_eq!(fs::read(segment_path(&directory, 3)).unwrap(), third);
        assert_eq!(
            read_after(&directory, 0)
                .unwrap()
                .events
                .iter()
                .map(|event| event.event_id)
                .collect::<Vec<_>>(),
            [1, 2, 3],
        );
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn recovery_still_rejects_a_compressed_only_newest_segment() {
        let directory = temporary_directory("recovery-compressed-active");
        let active = encode_event(1, protocol::event_type::PTY_OUTPUT, b"active").unwrap();
        write_compressed_bytes(&directory, 1, &active);

        let result = JournalWriter::recover(&directory, [7; 16], journal_config(1024, 4096));
        let error = match result {
            Ok(_) => panic!("compressed active segment was accepted"),
            Err(error) => error,
        };

        assert!(matches!(
            error,
            JournalError::Format(message)
                if message == "the newest journal segment must be active and uncompressed"
        ));
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn does_not_rotate_when_an_item_exactly_reaches_the_segment_limit() {
        let directory = temporary_directory("exact-segment-limit");
        let first = encode_event(1, protocol::event_type::PTY_OUTPUT, b"one").unwrap();
        let second = encode_event(2, protocol::event_type::PTY_OUTPUT, b"two").unwrap();
        let limit = u64::try_from(first.len() + second.len()).unwrap();
        let mut writer =
            JournalWriter::create(&directory, [7; 16], journal_config(limit, 1024)).unwrap();

        writer
            .append_at(1, protocol::event_type::PTY_OUTPUT, 1, 0, b"one")
            .unwrap();
        writer
            .append_at(2, protocol::event_type::PTY_OUTPUT, 1, 0, b"two")
            .unwrap();
        writer.flush().unwrap();

        assert_eq!(
            fs::read(directory.join("00000001.cbor")).unwrap(),
            [first, second].concat(),
        );
        assert!(!directory.join("00000002.cbor").exists());
        assert_eq!(writer.active_segment_number(), 1);
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn keeps_an_oversized_first_item_whole_then_rotates_before_the_next_item() {
        let directory = temporary_directory("oversized-item");
        let first = encode_event(1, protocol::event_type::PTY_OUTPUT, b"whole").unwrap();
        let second = encode_event(2, protocol::event_type::PTY_OUTPUT, b"next").unwrap();
        let limit = u64::try_from(first.len() - 1).unwrap();
        let mut writer =
            JournalWriter::create(&directory, [7; 16], journal_config(limit, 1024)).unwrap();

        writer
            .append_at(1, protocol::event_type::PTY_OUTPUT, 1, 0, b"whole")
            .unwrap();
        writer
            .append_at(2, protocol::event_type::PTY_OUTPUT, 1, 0, b"next")
            .unwrap();
        writer.flush().unwrap();
        writer.finish_maintenance().unwrap();

        assert_eq!(decode_compressed_segment(&directory, 1), first);
        assert_eq!(fs::read(directory.join("00000002.cbor")).unwrap(), second);
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn recovered_active_length_rotates_the_next_item_after_a_partial_tail() {
        let directory = temporary_directory("recovered-active-length");
        let first = encode_event(1, protocol::event_type::PTY_OUTPUT, b"one").unwrap();
        let second = encode_event(2, protocol::event_type::PTY_OUTPUT, b"two").unwrap();
        let partial = hex_bytes("830319010046706172");
        fs::write(
            directory.join("00000001.cbor"),
            [first.clone(), partial].concat(),
        )
        .unwrap();
        let limit = u64::try_from(first.len() + second.len() - 1).unwrap();

        let mut writer =
            JournalWriter::recover(&directory, [7; 16], journal_config(limit, 1024)).unwrap();
        writer
            .append_at(2, protocol::event_type::PTY_OUTPUT, 1, 0, b"two")
            .unwrap();
        writer.flush().unwrap();
        writer.finish_maintenance().unwrap();

        assert_eq!(decode_compressed_segment(&directory, 1), first);
        assert_eq!(fs::read(directory.join("00000002.cbor")).unwrap(), second);
        assert_eq!(writer.active_segment_number(), 2);
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn create_rejects_invalid_journal_limits() {
        for (segment_max_bytes, journal_max_bytes) in [(0, 1024), (1024, 1023)] {
            let directory = temporary_directory("invalid-create-limits");
            let result = JournalWriter::create(
                &directory,
                [7; 16],
                journal_config(segment_max_bytes, journal_max_bytes),
            );
            let error = match result {
                Ok(_) => panic!("invalid journal limits were accepted"),
                Err(error) => error,
            };

            assert!(matches!(error, JournalError::Configuration(_)));
            fs::remove_dir_all(directory).unwrap();
        }
    }

    #[test]
    fn recover_rejects_invalid_journal_limits() {
        for (segment_max_bytes, journal_max_bytes) in [(0, 1024), (1024, 1023)] {
            let directory = temporary_directory("invalid-recover-limits");
            fs::write(directory.join("00000001.cbor"), Vec::<u8>::new()).unwrap();
            let result = JournalWriter::recover(
                &directory,
                [7; 16],
                journal_config(segment_max_bytes, journal_max_bytes),
            );
            let error = match result {
                Ok(_) => panic!("invalid journal limits were accepted"),
                Err(error) => error,
            };

            assert!(matches!(error, JournalError::Configuration(_)));
            fs::remove_dir_all(directory).unwrap();
        }
    }

    #[test]
    fn writes_a_headerless_cbor_sequence_and_rotates_between_items() {
        let directory = temporary_directory("cbor-sequence");
        let mut writer = JournalWriter::create(&directory, [7; 16], JournalConfig::default()).unwrap();
        let first = writer
            .append_at(0, protocol::event_type::PTY_OUTPUT, 1, 0, &[0, 0x1b, 0xff])
            .unwrap();
        writer.rotate().unwrap();
        let second = writer
            .append_at(0, protocol::event_type::PTY_RESIZE, 1, 0, &protocol::pty_resize_payload(180, 50))
            .unwrap();
        writer.flush().unwrap();
        writer.finish_maintenance().unwrap();

        assert_eq!((first, second), (1, 2));
        assert_eq!(decode_compressed_segment(&directory, 1), hex_bytes("830119010043001bff"));
        assert_eq!(
            fs::read(directory.join("00000002.cbor")).unwrap(),
            hex_bytes("83021901028218b41832"),
        );
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn journal_writer_and_reader_match_the_shared_golden_sequence() {
        let directory = temporary_directory("writer-golden");
        let input_id = [
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d,
            0x0e, 0x0f,
        ];
        let mut writer = JournalWriter::create(&directory, [7; 16], JournalConfig::default()).unwrap();
        writer
            .append_at(1, protocol::event_type::PTY_OUTPUT, 1, 0, &[0, 0x1b, 0xff])
            .unwrap();
        writer
            .append_at(2, protocol::event_type::PTY_RESIZE, 1, 0, &protocol::pty_resize_payload(180, 50))
            .unwrap();
        writer
            .append_at(
                3,
                protocol::event_type::PTY_INPUT,
                1,
                0,
                &protocol::pty_input_payload(input_id, &[0, 0xff]).unwrap(),
            )
            .unwrap();
        writer
            .append_at(
                4,
                protocol::event_type::PROCESS_EXITED,
                1,
                0,
                &protocol::process_exited_payload(0, -1),
            )
            .unwrap();
        writer.flush().unwrap();

        let expected = hex_bytes(include_str!("../protocol/fixtures/session-events-v1.hex"));
        assert_eq!(fs::read(directory.join("00000001.cbor")).unwrap(), expected);
        let read = read_after(&directory, 0).unwrap();
        assert_eq!(read.events.iter().map(|event| event.event_id).collect::<Vec<_>>(), [1, 2, 3, 4]);
        assert_eq!(read.events[2].payload, [input_id.to_vec(), vec![0, 0xff]].concat());
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn rejects_invalid_known_payloads_on_write_and_read() {
        let directory = temporary_directory("invalid-known-payloads");
        let mut writer = JournalWriter::create(&directory, [7; 16], JournalConfig::default()).unwrap();
        assert!(writer
            .append_at(1, protocol::event_type::PTY_RESIZE, 1, 0, &protocol::pty_resize_payload(0, 50))
            .is_err());
        assert!(writer
            .append_at(
                1,
                protocol::event_type::PTY_RESIZE,
                1,
                0,
                &protocol::pty_resize_payload(65536, 50),
            )
            .is_err());
        assert!(writer
            .append_at(1, protocol::event_type::PROCESS_STARTED, 1, 0, &0_u64.to_le_bytes())
            .is_err());
        let mut invalid_signal = Vec::new();
        invalid_signal.extend_from_slice(&1_u16.to_le_bytes());
        invalid_signal.extend_from_slice(&0_u16.to_le_bytes());
        invalid_signal.extend_from_slice(&(-2_i32).to_le_bytes());
        assert!(writer
            .append_at(1, protocol::event_type::SIGNAL, 1, 0, &invalid_signal)
            .is_err());
        assert!(fs::read(directory.join("00000001.cbor")).unwrap().is_empty());
        let delivered_signal = crate::host::signal_payload(1, 0);
        assert_eq!(
            writer
                .append_at(1, protocol::event_type::SIGNAL, 1, 0, &delivered_signal)
                .unwrap(),
            1,
        );
        writer.flush().unwrap();
        assert_eq!(read_after(&directory, 0).unwrap().events[0].payload, delivered_signal);

        let non_uuid = protocol::encode_pty_input(1, "command-1", b"input").unwrap();
        assert!(parse_record(&non_uuid).is_err());
        let non_canonical =
            protocol::encode_pty_input(1, "00010203-0405-0607-0809-0A0B0C0D0E0F", b"input").unwrap();
        assert!(parse_record(&non_canonical).is_err());
        assert!(protocol::encode_signal(1, 1, 2).is_ok());
        assert!(protocol::encode_signal(1, 1, 0).is_ok());
        assert!(protocol::encode_signal(1, 1, -2).is_err());
        assert!(protocol::encode_signal(1, 6, -1).is_err());
        assert!(protocol::encode_signal(1, 0xffff, -1).is_err());
        for invalid in [
            "830119010282001832",
            "8301190102821a000100001832",
            "83011902008100",
            "8301190202820121",
            "8301190202820620",
            "83011902028219ffff20",
        ] {
            assert!(parse_record(&hex_bytes(invalid)).is_err(), "accepted invalid record {invalid}");
        }
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn rejects_definite_known_strings_over_the_semantic_payload_limit() {
        let oversized_output = vec![0; MAX_PAYLOAD_LENGTH + 1];
        let output_record = [
            hex_bytes("8301190100"),
            definite_string(2, &oversized_output),
        ]
        .concat();
        assert!(parse_record(&output_record).is_err());

        let input_bytes = vec![0; MAX_PAYLOAD_LENGTH - 15];
        let input_record = [
            hex_bytes("830219010182"),
            definite_string(3, b"00010203-0405-0607-0809-0a0b0c0d0e0f"),
            definite_string(2, &input_bytes),
        ]
        .concat();
        assert!(parse_record(&input_record).is_err());
    }

    #[test]
    fn rejects_invalid_utf8_in_opaque_payloads_and_future_fields() {
        let unknown_payload = hex_bytes("8301197ffe61ff");
        assert!(parse_record(&unknown_payload).is_err());

        let future_field_with_invalid_chunk = hex_bytes("840219010041617f61ffff");
        assert!(parse_record(&future_field_with_invalid_chunk).is_err());
    }

    #[test]
    fn accepts_trailing_fields_on_a_known_record_and_payload() {
        let encoded = hex_bytes("84051901028318b418320066667574757265");

        let event = parse_record(&encoded).unwrap();

        assert_eq!(event.event_id, 5);
        assert_eq!(event.payload, protocol::pty_resize_payload(180, 50));
        assert_eq!(event.trailing_field_count, 1);
        assert!(!event.opaque);
    }

    #[test]
    fn decodes_indefinite_known_string_payload_and_continues_to_later_records() {
        let directory = temporary_directory("indefinite-string");
        fs::write(
            directory.join("00000001.cbor"),
            [
                hex_bytes("83011901005f4268694300ff21ff"),
                hex_bytes("8302190100426f6b"),
            ]
            .concat(),
        )
        .unwrap();

        let read = read_after(&directory, 0).unwrap();

        assert_eq!(read.events.len(), 2);
        assert_eq!(read.events[0].payload, b"hi\0\xff!");
        assert_eq!(read.events[1].payload, b"ok");
        assert!(parse_record(&hex_bytes("83011901005f5f4161ffff")).is_err());
        assert!(parse_record(&hex_bytes("83011901005f6161ff")).is_err());
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn reports_a_gap_and_reads_after_ids_across_discovered_segments() {
        let directory = temporary_directory("read-after");
        fs::write(
            directory.join("00000004.cbor"),
            [hex_bytes("830a1901004161"), hex_bytes("830b1901004162")].concat(),
        )
        .unwrap();
        fs::write(directory.join("00000005.cbor"), hex_bytes("830f1901004163")).unwrap();

        let before = read_after(&directory, 5).unwrap();
        assert_eq!(before.gap.unwrap().first_available_event_id, 10);
        assert_eq!(before.events.iter().map(|event| event.event_id).collect::<Vec<_>>(), [10, 11, 15]);
        let within = read_after(&directory, 11).unwrap();
        assert!(within.gap.is_none());
        assert_eq!(within.events.iter().map(|event| event.event_id).collect::<Vec<_>>(), [15]);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn read_retries_after_compression_replaces_a_discovered_raw_segment() {
        let directory = temporary_directory("read-compression-race");
        let first = write_raw_event(&directory, 1, 1, b"first");
        write_raw_event(&directory, 2, 2, b"second");
        let mut attempts = 0;

        let read = read_after_with_hook(&directory, 0, |attempt, segments| {
            attempts += 1;
            assert_eq!(attempt, attempts - 1);
            if attempt == 0 {
                assert_eq!(segments[0].path, segment_path(&directory, 1));
                write_compressed_bytes(&directory, 1, &first);
                fs::remove_file(segment_path(&directory, 1)).unwrap();
            }
        })
        .unwrap();

        assert_eq!(attempts, 2);
        assert_eq!(
            read.gap,
            Some(RetentionGap {
                requested_event_id: 0,
                first_available_event_id: 1,
            })
        );
        assert_eq!(
            read.events.iter().map(|event| event.event_id).collect::<Vec<_>>(),
            [1, 2]
        );
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn read_retries_with_a_fresh_gap_floor_after_retention_removes_the_oldest_segment() {
        let directory = temporary_directory("read-retention-race");
        write_raw_event(&directory, 1, 1, b"first");
        write_raw_event(&directory, 2, 2, b"second");
        write_raw_event(&directory, 3, 3, b"third");
        let mut attempts = 0;

        let read = read_after_with_hook(&directory, 0, |attempt, segments| {
            attempts += 1;
            if attempt == 0 {
                assert_eq!(
                    segments.iter().map(|segment| segment.number).collect::<Vec<_>>(),
                    [1, 2, 3]
                );
                fs::remove_file(segment_path(&directory, 1)).unwrap();
            }
        })
        .unwrap();

        assert_eq!(attempts, 2);
        assert_eq!(
            read.gap,
            Some(RetentionGap {
                requested_event_id: 0,
                first_available_event_id: 2,
            })
        );
        assert_eq!(
            read.events.iter().map(|event| event.event_id).collect::<Vec<_>>(),
            [2, 3]
        );
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn read_returns_the_second_not_found_error_without_an_unbounded_retry() {
        let directory = temporary_directory("read-repeated-not-found");
        write_raw_event(&directory, 1, 1, b"first");
        write_raw_event(&directory, 2, 2, b"second");
        let mut attempts = 0;

        let result = read_after_with_hook(&directory, 0, |_attempt, segments| {
            attempts += 1;
            fs::remove_file(&segments[0].path).unwrap();
        });

        assert_eq!(attempts, 2);
        assert!(matches!(
            result,
            Err(JournalError::Io(error)) if error.kind() == io::ErrorKind::NotFound
        ));
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn read_does_not_retry_a_non_not_found_format_error() {
        let directory = temporary_directory("read-format-race");
        write_raw_event(&directory, 1, 1, b"first");
        let mut attempts = 0;

        let result = read_after_with_hook(&directory, 0, |_attempt, segments| {
            attempts += 1;
            fs::write(&segments[0].path, [0xff]).unwrap();
        });

        assert_eq!(attempts, 1);
        assert!(matches!(result, Err(JournalError::Format(_))));
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn recovers_and_truncates_only_a_partial_active_tail() {
        let directory = temporary_directory("recover-cbor-tail");
        let complete = hex_bytes("830719010044676f6f64");
        let partial = hex_bytes("830819010046706172");
        fs::write(directory.join("00000001.cbor"), [complete.clone(), partial].concat()).unwrap();

        let read = read_after(&directory, 0).unwrap();
        assert_eq!(read.events.len(), 1);
        assert!(read.ignored_crash_tail);
        let mut writer = JournalWriter::recover(&directory, [9; 16], JournalConfig::default()).unwrap();
        assert_eq!(fs::read(directory.join("00000001.cbor")).unwrap(), complete);
        assert_eq!(
            writer
                .append_at(7, protocol::event_type::PTY_OUTPUT, 1, 0, b"next")
                .unwrap(),
            8,
        );
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn reads_and_recovers_an_active_segment_with_only_a_partial_first_item() {
        let directory = temporary_directory("partial-first-item");
        fs::write(directory.join("00000001.cbor"), hex_bytes("830119010046706172")).unwrap();

        let read = read_after(&directory, 0).unwrap();
        assert!(read.events.is_empty());
        assert!(read.gap.is_none());
        assert!(read.ignored_crash_tail);

        let mut writer = JournalWriter::recover(&directory, [4; 16], JournalConfig::default()).unwrap();
        assert!(fs::read(directory.join("00000001.cbor")).unwrap().is_empty());
        assert_eq!(
            writer
                .append_at(0, protocol::event_type::PTY_OUTPUT, 1, 0, b"complete")
                .unwrap(),
            1,
        );
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn read_after_does_not_scan_records_before_the_selected_segment() {
        let directory = temporary_directory("selected-segment");
        fs::write(
            directory.join("00000001.cbor"),
            [hex_bytes("8301190100456669727374"), vec![0xff]].concat(),
        )
        .unwrap();
        fs::write(
            directory.join("00000002.cbor"),
            [
                hex_bytes("830a1901004574656e7468"),
                hex_bytes("830b19010046656c6576656e"),
            ]
            .concat(),
        )
        .unwrap();

        let read = read_after(&directory, 10).unwrap();
        assert!(read.gap.is_none());
        assert_eq!(read.events.iter().map(|event| event.event_id).collect::<Vec<_>>(), [11]);
        assert_eq!(read.events[0].payload, b"eleven");
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn preserves_unknown_records_with_trailing_fields_and_continues() {
        let directory = temporary_directory("unknown-cbor");
        fs::write(
            directory.join("00000001.cbor"),
            [
                hex_bytes("8405197ffe44deadbeef66667574757265"),
                hex_bytes("8306190100426f6b"),
            ]
            .concat(),
        )
        .unwrap();

        let read = read_after(&directory, 0).unwrap();
        assert_eq!(read.events.len(), 2);
        assert!(read.events[0].opaque);
        assert_eq!(read.events[0].trailing_field_count, 1);
        assert_eq!(read.events[0].encoded_record, hex_bytes("8405197ffe44deadbeef66667574757265"));
        assert_eq!(read.events[1].payload, b"ok");
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn discovers_and_reads_compressed_closed_segments_without_an_index() {
        let directory = temporary_directory("compressed-segment");
        let closed = [
            hex_bytes("8301190100456669727374"),
            hex_bytes("8302190100467365636f6e64"),
        ]
        .concat();
        let compressed = zstd::stream::encode_all(closed.as_slice(), 1).unwrap();
        fs::write(directory.join("00000007.cbor.zst"), compressed).unwrap();
        fs::write(directory.join("00000008.cbor"), hex_bytes("8303190100457468697264")).unwrap();

        let result = read_after(&directory, 1).unwrap();
        assert!(result.gap.is_none());
        assert_eq!(
            result.events.iter().map(|event| event.event_id).collect::<Vec<_>>(),
            [2, 3],
        );
        assert_eq!(result.events[0].payload, b"second");
        assert_eq!(result.events[1].payload, b"third");
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn compressed_first_event_discovery_does_not_consume_a_large_failing_remainder() {
        struct FirstItemThenFail {
            first_item: Option<Vec<u8>>,
            unread_remainder_length: u64,
            remainder_requested: bool,
        }

        impl Read for FirstItemThenFail {
            fn read(&mut self, target: &mut [u8]) -> io::Result<usize> {
                if let Some(first_item) = self.first_item.take() {
                    target[..first_item.len()].copy_from_slice(&first_item);
                    return Ok(first_item.len());
                }
                self.remainder_requested = true;
                Err(io::Error::new(
                    io::ErrorKind::Other,
                    format!(
                        "attempted to decode {} bytes after the first item",
                        self.unread_remainder_length
                    ),
                ))
            }
        }

        let mut decoded = FirstItemThenFail {
            first_item: Some(hex_bytes("83182a190100456669727374")),
            unread_remainder_length: MAX_DECOMPRESSED_SEGMENT_LENGTH,
            remainder_requested: false,
        };

        assert_eq!(first_event_id_from_decoded_stream(&mut decoded).unwrap(), Some(42));
        assert!(!decoded.remainder_requested);
    }

    #[test]
    fn compressed_raw_comparison_stops_when_the_decoded_limit_is_exceeded() {
        struct ChunkedReader {
            bytes: Vec<u8>,
            position: usize,
            max_chunk: usize,
        }

        impl Read for ChunkedReader {
            fn read(&mut self, target: &mut [u8]) -> io::Result<usize> {
                let remaining = self.bytes.len() - self.position;
                let length = remaining.min(target.len()).min(self.max_chunk);
                target[..length]
                    .copy_from_slice(&self.bytes[self.position..self.position + length]);
                self.position += length;
                Ok(length)
            }
        }

        let bytes = (0_u8..64).collect::<Vec<_>>();
        let mut decoded = ChunkedReader {
            bytes: bytes.clone(),
            position: 0,
            max_chunk: 3,
        };
        let mut raw = ChunkedReader {
            bytes,
            position: 0,
            max_chunk: 1,
        };

        assert!(!decoded_stream_matches_raw(&mut decoded, &mut raw, 4).unwrap());
        assert_eq!(decoded.position, 6);
        assert_eq!(raw.position, 3);
    }

    #[test]
    fn rejects_corruption_instead_of_treating_it_as_a_tail() {
        let directory = temporary_directory("corrupt-cbor");
        fs::write(directory.join("00000001.cbor"), [0xff]).unwrap();

        assert!(read_after(&directory, 0).is_err());
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn metadata_round_trips_only_manifest_fields() {
        let directory = temporary_directory("metadata");
        let metadata = Metadata {
            metadata_version: 1,
            journal_format_version: 1,
            control_protocol_version: 1,
            session_id: "session-1".to_owned(),
            created_at_epoch_millis: 1,
            session_start_epoch_millis: 2,
            command: vec!["bash".to_owned(), String::new()],
            cwd: "/work".to_owned(),
            host_pid: 10,
            child_pid: None,
            initial_cols: 160,
            initial_rows: 50,
            current_cols: 160,
            current_rows: 50,
            term: "xterm-256color".to_owned(),
            sandbox: SandboxMetadata {
                requested: false,
                enforcement: SandboxEnforcement::None,
                unavailable_policy: SandboxUnavailablePolicy::Fail,
                read_write_paths: vec![],
                read_only_paths: vec![],
                policy_version: None,
                handled_rights: None,
                rules: vec![],
            },
            control: ControlMetadata {
                transport: ControlTransport::UnixDomainSocket,
                endpoint: "control.sock".to_owned(),
            },
        };
        write_metadata(&directory, &metadata, Durability::EveryRecord).unwrap();
        let encoded = fs::read_to_string(directory.join(METADATA_NAME)).unwrap();
        for removed in [
            "journalId",
            "state",
            "activeSegment",
            "oldestAvailableEventId",
            "latestEventId",
        ] {
            assert!(!encoded.contains(removed), "metadata contains {removed}");
        }
        assert_eq!(read_metadata(&directory).unwrap(), metadata);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn metadata_reader_ignores_a_future_field() {
        let directory = temporary_directory("future-metadata");
        let mut value: serde_json::Value = serde_json::from_str(include_str!(
            "../protocol/fixtures/metadata-v1.json"
        ))
        .unwrap();
        value
            .as_object_mut()
            .unwrap()
            .insert("futureField".to_owned(), serde_json::json!(true));
        fs::write(directory.join(METADATA_NAME), serde_json::to_vec(&value).unwrap()).unwrap();

        assert_eq!(read_metadata(&directory).unwrap().session_id, "019d-session-fixture");
        fs::remove_dir_all(directory).unwrap();
    }
}
