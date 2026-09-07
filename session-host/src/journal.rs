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

#[derive(Clone, Debug)]
pub struct JournalConfig {
    pub segment_max_bytes: u64,
    pub journal_max_bytes: u64,
}

impl Default for JournalConfig {
    fn default() -> Self {
        Self {
            segment_max_bytes: DEFAULT_JOURNAL_SEGMENT_BYTES,
            journal_max_bytes: DEFAULT_JOURNAL_MAX_BYTES,
        }
    }
}

#[derive(Debug)]
pub enum JournalError {
    Io(io::Error),
    Configuration(String),
    Format(String),
    Maintenance(String),
    Finished,
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
            Self::Finished => formatter.write_str("journal has already finished"),
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
    ApplyRetention {
        active_segment: u64,
        acknowledged_event_id: u64,
        result: Sender<Result<(), JournalError>>,
    },
    Finish(u64),
}

trait MaintenanceFileSystem: Send + Sync {
    fn rename(&self, source: &Path, target: &Path) -> io::Result<()>;

    fn remove_file(&self, path: &Path) -> io::Result<()>;

    fn sync_file(&self, path: &Path) -> io::Result<()> {
        OpenOptions::new().write(true).open(path)?.sync_all()
    }

    fn sync_directory(&self, directory: &Path) -> io::Result<()> {
        sync_directory(directory)
    }
}

trait RecordFileSync: Send + Sync {
    fn sync_data(&self, file: &File) -> io::Result<()>;

    fn sync_directory(&self, directory: &Path) -> io::Result<()> {
        sync_directory(directory)
    }

    fn remove_file(&self, path: &Path) -> io::Result<()> {
        fs::remove_file(path)
    }
}

struct RealMaintenanceFileSystem;

struct RealRecordFileSync;

impl MaintenanceFileSystem for RealMaintenanceFileSystem {
    fn rename(&self, source: &Path, target: &Path) -> io::Result<()> {
        fs::rename(source, target)
    }

    fn remove_file(&self, path: &Path) -> io::Result<()> {
        fs::remove_file(path)
    }
}

impl RecordFileSync for RealRecordFileSync {
    fn sync_data(&self, file: &File) -> io::Result<()> {
        file.sync_data()
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

    fn apply_retention_through(
        &self,
        active_segment: u64,
        acknowledged_event_id: u64,
    ) -> Result<(), JournalError> {
        self.ensure_running()?;
        let (sender, receiver) = mpsc::channel();
        self.sender
            .send(MaintenanceCommand::ApplyRetention {
                active_segment,
                acknowledged_event_id,
                result: sender,
            })
            .map_err(|_| {
                JournalError::Maintenance("cannot schedule acknowledged retention".to_owned())
            })?;
        receiver.recv().map_err(|_| {
            JournalError::Maintenance("acknowledged retention worker stopped".to_owned())
        })?
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
    previous_event_id: u64,
    segment_number: u64,
    active_length: u64,
    session_start: Instant,
    config: JournalConfig,
    maintenance: JournalMaintenance,
    record_file_sync: Arc<dyn RecordFileSync>,
    append_failure: Option<String>,
    finished: bool,
}

impl JournalWriter {
    pub fn create(
        directory: impl AsRef<Path>,
        config: JournalConfig,
    ) -> Result<Self, JournalError> {
        Self::create_with_maintenance_file_system(
            directory,
            config,
            Arc::new(RealMaintenanceFileSystem),
        )
    }

    fn create_with_maintenance_file_system(
        directory: impl AsRef<Path>,
        config: JournalConfig,
        file_system: Arc<dyn MaintenanceFileSystem>,
    ) -> Result<Self, JournalError> {
        Self::create_with_file_systems(
            directory,
            config,
            file_system,
            Arc::new(RealRecordFileSync),
        )
    }

    #[cfg(test)]
    fn create_with_record_file_sync(
        directory: impl AsRef<Path>,
        config: JournalConfig,
        record_file_sync: Arc<dyn RecordFileSync>,
    ) -> Result<Self, JournalError> {
        Self::create_with_file_systems(
            directory,
            config,
            Arc::new(RealMaintenanceFileSystem),
            record_file_sync,
        )
    }

    fn create_with_file_systems(
        directory: impl AsRef<Path>,
        config: JournalConfig,
        file_system: Arc<dyn MaintenanceFileSystem>,
        record_file_sync: Arc<dyn RecordFileSync>,
    ) -> Result<Self, JournalError> {
        validate_config(&config)?;
        let directory = directory.as_ref().to_path_buf();
        create_session_directory_with_sync(&directory, record_file_sync.as_ref())?;
        let segment_number = FIRST_SEGMENT;
        let file = create_segment(&directory, segment_number, record_file_sync.as_ref())
            .map_err(initial_segment_error)?;
        let maintenance = JournalMaintenance::start_with_file_system(
            directory.clone(),
            segment_number,
            config.journal_max_bytes,
            file_system,
        )?;
        Ok(Self {
            directory,
            file,
            previous_event_id: 0,
            segment_number,
            active_length: 0,
            session_start: Instant::now(),
            config,
            maintenance,
            record_file_sync,
            append_failure: None,
            finished: false,
        })
    }

    pub fn append_buffered(
        &mut self,
        event_type: u16,
        payload: &[u8],
    ) -> Result<u64, JournalError> {
        self.reject_generic_process_exit(event_type)?;
        self.append_record(event_type, payload, false)
    }

    pub fn append_durable(
        &mut self,
        event_type: u16,
        payload: &[u8],
    ) -> Result<u64, JournalError> {
        self.reject_generic_process_exit(event_type)?;
        self.append_record(event_type, payload, true)
    }

    pub fn finish_durably(&mut self, exit_code: i32) -> Result<u64, JournalError> {
        let payload = protocol::process_exited_payload(exit_code, -1);
        let event_id = self.append_record(protocol::event_type::PROCESS_EXITED, &payload, true)?;
        self.finished = true;
        Ok(event_id)
    }

    fn reject_generic_process_exit(&self, event_type: u16) -> Result<(), JournalError> {
        if event_type == protocol::event_type::PROCESS_EXITED {
            return Err(JournalError::Format(
                "PROCESS_EXITED must be written with finish_durably".to_owned(),
            ));
        }
        Ok(())
    }

    fn append_record(
        &mut self,
        event_type: u16,
        payload: &[u8],
        force_sync: bool,
    ) -> Result<u64, JournalError> {
        let elapsed = self.session_start.elapsed().as_nanos();
        let raw_event_id = u64::try_from(elapsed).unwrap_or(u64::MAX);
        self.append_at(
            raw_event_id,
            event_type,
            payload,
            force_sync,
        )
    }

    #[cfg(test)]
    fn append_at_for_test(
        &mut self,
        raw_event_id: u64,
        event_type: u16,
        payload: &[u8],
    ) -> Result<u64, JournalError> {
        self.append_at(raw_event_id, event_type, payload, false)
    }

    fn append_at(
        &mut self,
        raw_event_id: u64,
        event_type: u16,
        payload: &[u8],
        force_sync: bool,
    ) -> Result<u64, JournalError> {
        self.ensure_accepting_records()?;
        self.maintenance.ensure_running()?;
        if payload.len() > MAX_PAYLOAD_LENGTH {
            return Err(JournalError::PayloadTooLarge(payload.len()));
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
            self.rotate_active_segment()?;
        }
        let initial_length = self.active_length;
        if let Err(error) = self.file.write_all(&record) {
            return Err(self.rollback_failed_append(initial_length, error, force_sync));
        }
        if force_sync && let Err(error) = self.record_file_sync.sync_data(&self.file)
        {
            return Err(self.rollback_failed_append(initial_length, error, true));
        }
        self.active_length = if self.active_length == 0 {
            record_length
        } else {
            next_length
        };
        self.previous_event_id = event_id;
        Ok(event_id)
    }

    fn ensure_accepting_records(&self) -> Result<(), JournalError> {
        if let Some(message) = &self.append_failure {
            return Err(JournalError::Maintenance(message.clone()));
        }
        if self.finished {
            return Err(JournalError::Finished);
        }
        Ok(())
    }

    #[cfg(test)]
    fn rotate(&mut self) -> Result<(), JournalError> {
        self.rotate_active_segment()
    }

    fn rotate_active_segment(&mut self) -> Result<(), JournalError> {
        self.ensure_accepting_records()?;
        self.maintenance.ensure_running()?;
        self.file.flush()?;
        self.record_file_sync.sync_data(&self.file)?;
        let number = self
            .segment_number
            .checked_add(1)
            .ok_or_else(|| JournalError::Format("segment number is exhausted".to_owned()))?;
        let file = match create_segment(&self.directory, number, self.record_file_sync.as_ref()) {
            Ok(file) => file,
            Err(SegmentCreateError::Create(error))
                if error.kind() == io::ErrorKind::AlreadyExists =>
            {
                return Err(self.poison(format!(
                    "cannot publish successor segment {number}: it already exists"
                )));
            }
            Err(SegmentCreateError::Create(error)) => return Err(JournalError::Io(error)),
            Err(SegmentCreateError::Publication { error, cleanup: None }) => {
                return Err(JournalError::Io(error));
            }
            Err(SegmentCreateError::Publication {
                error,
                cleanup: Some(cleanup),
            }) => {
                return Err(self.poison(format!(
                    "cannot continue after successor publication failed ({error}) \
                     and cleanup failed ({cleanup})"
                )));
            }
        };
        self.file = file;
        self.segment_number = number;
        self.active_length = 0;
        self.maintenance.reconcile(number);
        Ok(())
    }

    fn rollback_failed_append(
        &mut self,
        initial_length: u64,
        append_error: io::Error,
        synchronize: bool,
    ) -> JournalError {
        let rollback = (|| -> io::Result<()> {
            self.file.set_len(initial_length)?;
            self.file.seek(SeekFrom::End(0))?;
            if synchronize {
                self.record_file_sync.sync_data(&self.file)?;
            }
            Ok(())
        })();
        match rollback {
            Ok(()) => JournalError::Io(append_error),
            Err(rollback_error) => {
                let message = format!(
                    "cannot continue after durable append failed ({append_error}) \
                     and rollback failed ({rollback_error})"
                );
                self.append_failure = Some(message.clone());
                JournalError::Maintenance(message)
            }
        }
    }

    fn poison(&mut self, message: String) -> JournalError {
        self.append_failure = Some(message.clone());
        JournalError::Maintenance(message)
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

    pub fn apply_retention_through(
        &mut self,
        acknowledged_event_id: u64,
    ) -> Result<(), JournalError> {
        self.maintenance
            .apply_retention_through(self.segment_number, acknowledged_event_id)
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
    journal_max_bytes: u64,
    file_system: Arc<dyn MaintenanceFileSystem>,
    receiver: Receiver<MaintenanceCommand>,
) -> Result<(), JournalError> {
    let mut pending_error = None;
    let mut acknowledged_event_id = None;
    while let Ok(command) = receiver.recv() {
        match command {
            MaintenanceCommand::Reconcile(active_segment) => {
                let result = reconcile_journal(
                    directory,
                    active_segment,
                    journal_max_bytes,
                    acknowledged_event_id,
                    file_system.as_ref(),
                );
                match result {
                    Ok(()) => pending_error = None,
                    Err(error) => pending_error = Some(error),
                }
            }
            MaintenanceCommand::ApplyRetention {
                active_segment,
                acknowledged_event_id: requested,
                result,
            } => {
                acknowledged_event_id = Some(
                    acknowledged_event_id
                        .map_or(requested, |current: u64| current.max(requested)),
                );
                let applied = reconcile_journal(
                    directory,
                    active_segment,
                    journal_max_bytes,
                    acknowledged_event_id,
                    file_system.as_ref(),
                );
                if applied.is_ok() {
                    pending_error = None;
                }
                let _ = result.send(applied);
            }
            MaintenanceCommand::Finish(active_segment) => {
                return reconcile_journal(
                    directory,
                    active_segment,
                    journal_max_bytes,
                    acknowledged_event_id,
                    file_system.as_ref(),
                );
            }
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
    journal_max_bytes: u64,
    acknowledged_event_id: Option<u64>,
    file_system: &dyn MaintenanceFileSystem,
) -> Result<(), JournalError> {
    compress_closed_segments(directory, active_segment, file_system)?;
    enforce_retention(
        directory,
        active_segment,
        journal_max_bytes,
        acknowledged_event_id,
        file_system,
    )
}

fn compress_closed_segments(
    directory: &Path,
    active_segment: u64,
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
        reconcile_closed_segment(directory, number, &raw_path, file_system)?;
    }
    Ok(())
}

fn reconcile_closed_segment(
    directory: &Path,
    number: u64,
    raw_path: &Path,
    file_system: &dyn MaintenanceFileSystem,
) -> Result<(), JournalError> {
    let temporary = compressed_temporary_segment_path(directory, number);
    if temporary.try_exists()? {
        file_system.remove_file(&temporary)?;
        file_system.sync_directory(directory)?;
    }
    let compressed = compressed_segment_path(directory, number);
    if compressed.try_exists()? {
        if published_compression_matches_raw(&compressed, raw_path)? {
            file_system.sync_file(&compressed)?;
            file_system.sync_directory(directory)?;
            file_system.remove_file(raw_path)?;
            file_system.sync_directory(directory)?;
            return Ok(());
        }
        file_system.remove_file(&compressed)?;
        file_system.sync_directory(directory)?;
    }
    compress_segment(directory, number, raw_path, file_system)
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

fn compress_segment(
    directory: &Path,
    number: u64,
    raw_path: &Path,
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
    encoder.finish()?;
    file_system.sync_file(&temporary)?;
    file_system.rename(&temporary, &compressed_segment_path(directory, number))?;
    file_system.sync_directory(directory)?;
    file_system.remove_file(raw_path)?;
    file_system.sync_directory(directory)?;
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

pub(crate) fn create_session_directory_durably(
    directory: impl AsRef<Path>,
) -> Result<(), JournalError> {
    create_session_directory_with_sync(directory.as_ref(), &RealRecordFileSync)
}

fn create_session_directory_with_sync(
    directory: &Path,
    record_file_sync: &dyn RecordFileSync,
) -> Result<(), JournalError> {
    let directory = if directory.is_absolute() {
        directory.to_path_buf()
    } else {
        std::env::current_dir()?.join(directory)
    };
    let mut missing = Vec::new();
    let mut ancestor = directory.as_path();
    while !ancestor.try_exists()? {
        missing.push(ancestor.to_path_buf());
        ancestor = ancestor.parent().ok_or_else(|| {
            JournalError::Configuration(
                "session path has no pre-existing ancestor to serve as its durable root".to_owned(),
            )
        })?;
    }
    if !ancestor.is_dir() {
        return Err(JournalError::Configuration(format!(
            "session path ancestor is not a directory: {}",
            ancestor.display()
        )));
    }
    if let Some(parent) = ancestor.parent() {
        record_file_sync.sync_directory(parent)?;
    }
    for path in missing.into_iter().rev() {
        match fs::create_dir(&path) {
            Ok(()) => {}
            Err(error) if error.kind() == io::ErrorKind::AlreadyExists && path.is_dir() => {}
            Err(error) => return Err(JournalError::Io(error)),
        }
        let parent = path.parent().ok_or_else(|| {
            JournalError::Configuration("session path component has no parent".to_owned())
        })?;
        record_file_sync.sync_directory(parent)?;
    }
    Ok(())
}

enum SegmentCreateError {
    Create(io::Error),
    Publication {
        error: io::Error,
        cleanup: Option<io::Error>,
    },
}

fn initial_segment_error(error: SegmentCreateError) -> JournalError {
    match error {
        SegmentCreateError::Create(error)
        | SegmentCreateError::Publication {
            error,
            cleanup: None,
        } => JournalError::Io(error),
        SegmentCreateError::Publication {
            error,
            cleanup: Some(cleanup),
        } => JournalError::Maintenance(format!(
            "initial segment publication failed ({error}) and cleanup failed ({cleanup})"
        )),
    }
}

fn create_segment(
    directory: &Path,
    number: u64,
    record_file_sync: &dyn RecordFileSync,
) -> Result<File, SegmentCreateError> {
    let path = segment_path(directory, number);
    let file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&path)
        .map_err(SegmentCreateError::Create)?;
    if let Err(error) = record_file_sync.sync_directory(directory) {
        drop(file);
        let cleanup = record_file_sync
            .remove_file(&path)
            .and_then(|()| record_file_sync.sync_directory(directory))
            .err();
        return Err(SegmentCreateError::Publication { error, cleanup });
    }
    Ok(file)
}

fn encode_event(event_id: u64, event_type: u16, payload: &[u8]) -> Result<Vec<u8>, JournalError> {
    let encoded = match event_type {
        protocol::event_type::COMMAND_ACCEPTED => encode_command_accepted_event(event_id, payload),
        protocol::event_type::COMMAND_RESULT => encode_command_result_event(event_id, payload),
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
        protocol::event_type::SESSION_START_FAILED => {
            let (command_id, diagnostic, omitted_byte_count) = start_failure_fields(payload)?;
            protocol::encode_session_start_failed(
                event_id,
                command_id,
                diagnostic,
                omitted_byte_count,
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

fn encode_command_accepted_event(
    event_id: u64,
    payload: &[u8],
) -> Result<Vec<u8>, protocol::EncodeError> {
    if payload.len() <= 8 {
        return Err(protocol::EncodeError::InvalidPayload(
            "COMMAND_ACCEPTED payload must contain a sequence and envelope",
        ));
    }
    let operation_sequence = u64::from_le_bytes(payload[0..8].try_into().unwrap());
    protocol::encode_command_accepted(event_id, operation_sequence, &payload[8..])
}

fn encode_command_result_event(
    event_id: u64,
    payload: &[u8],
) -> Result<Vec<u8>, protocol::EncodeError> {
    if payload.len() < 12 {
        return Err(protocol::EncodeError::InvalidPayload(
            "COMMAND_RESULT payload is truncated",
        ));
    }
    let operation_sequence = u64::from_le_bytes(payload[0..8].try_into().unwrap());
    let command_id_length = usize::from(u16::from_le_bytes(payload[8..10].try_into().unwrap()));
    let command_id_end = 10_usize.checked_add(command_id_length).ok_or(
        protocol::EncodeError::InvalidPayload("COMMAND_RESULT command ID length overflows"),
    )?;
    let outcome_index = command_id_end;
    let detail_start = outcome_index.checked_add(1).ok_or(
        protocol::EncodeError::InvalidPayload("COMMAND_RESULT payload length overflows"),
    )?;
    if payload.len() < detail_start {
        return Err(protocol::EncodeError::InvalidPayload(
            "COMMAND_RESULT payload is truncated",
        ));
    }
    let outcome = command_outcome(u64::from(payload[outcome_index]))?;
    let detail = std::str::from_utf8(&payload[detail_start..]).map_err(|_| {
        protocol::EncodeError::InvalidPayload("COMMAND_RESULT detail is not valid UTF-8")
    })?;
    protocol::encode_command_result(
        event_id,
        operation_sequence,
        &payload[10..command_id_end],
        outcome,
        detail,
    )
}

fn command_outcome(value: u64) -> Result<protocol::CommandOutcome, protocol::EncodeError> {
    match value {
        1 => Ok(protocol::CommandOutcome::Succeeded),
        2 => Ok(protocol::CommandOutcome::Failed),
        3 => Ok(protocol::CommandOutcome::Rejected),
        4 => Ok(protocol::CommandOutcome::Ambiguous),
        _ => Err(protocol::EncodeError::InvalidPayload(
            "COMMAND_RESULT outcome is invalid",
        )),
    }
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
    last_event_id: u64,
    physical_bytes: u64,
    active: bool,
}

impl SegmentSize {
    fn closed(number: u64, last_event_id: u64, physical_bytes: u64) -> Self {
        Self {
            number,
            last_event_id,
            physical_bytes,
            active: false,
        }
    }

    fn active(number: u64, last_event_id: u64, physical_bytes: u64) -> Self {
        Self {
            number,
            last_event_id,
            physical_bytes,
            active: true,
        }
    }
}

fn retention_deletions(
    segments: &[SegmentSize],
    journal_max_bytes: u64,
    acknowledged_event_id: Option<u64>,
) -> Result<Vec<u64>, JournalError> {
    let mut total = 0_u64;
    for segment in segments {
        total = total.checked_add(segment.physical_bytes).ok_or_else(|| {
            JournalError::Maintenance("physical journal size overflow".to_owned())
        })?;
    }
    let mut deletions = Vec::new();
    let Some(acknowledged_event_id) = acknowledged_event_id else {
        return Ok(deletions);
    };
    for segment in segments {
        if total <= journal_max_bytes
            || segment.active
            || segment.last_event_id > acknowledged_event_id
        {
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
    journal_max_bytes: u64,
    acknowledged_event_id: Option<u64>,
    file_system: &dyn MaintenanceFileSystem,
) -> Result<(), JournalError> {
    let segments = discover_segments(directory)?;
    let relevant = segments
        .iter()
        .take_while(|segment| segment.number <= active_segment)
        .cloned()
        .collect::<Vec<_>>();
    let scans = scan_segments(&relevant)?;
    let mut sizes = Vec::new();
    for (segment, scan) in relevant.iter().zip(scans) {
        let physical_bytes = fs::metadata(&segment.path)?.len();
        if segment.number == active_segment {
            sizes.push(SegmentSize::active(segment.number, 0, physical_bytes));
        } else {
            sizes.push(SegmentSize::closed(
                segment.number,
                scan.last_event_id,
                physical_bytes,
            ));
        }
    }
    let deletions = retention_deletions(&sizes, journal_max_bytes, acknowledged_event_id)?;
    for number in deletions {
        let segment = segments
            .iter()
            .find(|segment| segment.number == number)
            .ok_or_else(|| JournalError::Maintenance("retention segment disappeared".to_owned()))?;
        file_system.remove_file(&segment.path)?;
        sync_directory(directory)?;
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
    for (index, segment) in segments.iter().enumerate() {
        let allow_tail = index + 1 == segments.len() && !segment.compressed;
        let scan = scan_path(segment, ScanExtent::FirstRecord, allow_tail, 0)?;
        if scan.first_event_id.is_some() {
            return Ok(scan.first_event_id);
        }
    }
    Ok(None)
}

fn scan_segments(segments: &[SegmentFile]) -> Result<Vec<SegmentScan>, JournalError> {
    let mut scans = Vec::with_capacity(segments.len());
    let mut previous_event_id = 0;
    for (index, segment) in segments.iter().enumerate() {
        let allow_tail = index + 1 == segments.len() && !segment.compressed;
        let scan = scan_path(segment, ScanExtent::AllRecords, allow_tail, previous_event_id)?;
        previous_event_id = scan.last_event_id;
        scans.push(scan);
    }
    Ok(scans)
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct SegmentScan {
    first_event_id: Option<u64>,
    ignored_crash_tail: bool,
    last_event_id: u64,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum ScanExtent {
    FirstRecord,
    AllRecords,
}

fn scan_path(
    segment: &SegmentFile,
    extent: ScanExtent,
    allow_tail: bool,
    initial_event_id: u64,
) -> Result<SegmentScan, JournalError> {
    let file = File::open(&segment.path)?;
    if segment.compressed {
        let mut decoder = zstd::stream::read::Decoder::new(file)?;
        let decoded_limit = (extent == ScanExtent::AllRecords).then_some(MAX_DECOMPRESSED_SEGMENT_LENGTH);
        return scan_reader(
            &mut decoder,
            extent,
            allow_tail,
            initial_event_id,
            decoded_limit,
        );
    }
    scan_reader(
        &mut io::BufReader::new(file),
        extent,
        allow_tail,
        initial_event_id,
        None,
    )
}

fn scan_reader(
    reader: &mut impl Read,
    extent: ScanExtent,
    allow_tail: bool,
    initial_event_id: u64,
    decoded_limit: Option<u64>,
) -> Result<SegmentScan, JournalError> {
    let mut pending = Vec::new();
    let mut chunk = [0_u8; 4096];
    let mut decoded_bytes = 0_u64;
    let mut previous_event_id = initial_event_id;
    let mut first_event_id = None;
    let mut ignored_crash_tail = false;
    loop {
        match item_end(&pending, 0, 0) {
            Ok(end) => {
                if end > MAX_ENCODED_RECORD_LENGTH {
                    return Err(JournalError::Format("CBOR journal record is too large".to_owned()));
                }
                let event_id = parse_record_structure(&pending[..end])?;
                if event_id == 0 || event_id <= previous_event_id {
                    return Err(JournalError::Format(
                        "journal event IDs are not strictly increasing".to_owned(),
                    ));
                }
                first_event_id.get_or_insert(event_id);
                previous_event_id = event_id;
                if extent == ScanExtent::FirstRecord {
                    break;
                }
                pending.drain(..end);
            }
            Err(ParseFailure::Invalid(message)) => {
                return Err(JournalError::Format(message.to_owned()));
            }
            Err(ParseFailure::Incomplete) => {
                if pending.len() > MAX_ENCODED_RECORD_LENGTH {
                    return Err(JournalError::Format("CBOR journal record is too large".to_owned()));
                }
                if decoded_limit.is_some_and(|limit| decoded_bytes > limit) {
                    return Err(JournalError::Format(
                        "decompressed journal segment exceeds the size limit".to_owned(),
                    ));
                }
                let capacity = decoded_limit
                    .map(|limit| limit.saturating_add(1).saturating_sub(decoded_bytes))
                    .and_then(|remaining| usize::try_from(remaining).ok())
                    .map_or(chunk.len(), |remaining| chunk.len().min(remaining));
                let length = reader.read(&mut chunk[..capacity])?;
                if length == 0 {
                    if pending.is_empty() {
                        break;
                    }
                    if allow_tail {
                        ignored_crash_tail = true;
                        break;
                    }
                    return Err(JournalError::Format(
                        "segment contains an incomplete CBOR item".to_owned(),
                    ));
                }
                decoded_bytes += length as u64;
                if decoded_limit.is_some_and(|limit| decoded_bytes > limit) {
                    return Err(JournalError::Format(
                        "decompressed journal segment exceeds the size limit".to_owned(),
                    ));
                }
                pending.extend_from_slice(&chunk[..length]);
            }
        }
    }
    Ok(SegmentScan {
        first_event_id,
        ignored_crash_tail,
        last_event_id: previous_event_id,
    })
}

fn parse_record_structure(encoded: &[u8]) -> Result<u64, JournalError> {
    let fields = array_fields(encoded)?;
    if fields.len() < 3 {
        return Err(JournalError::Format(
            "session event must contain at least three fields".to_owned(),
        ));
    }
    let event_id = decode_unsigned(&encoded[fields[0].0..fields[0].1], "eventId")?;
    let event_type_value = decode_unsigned(&encoded[fields[1].0..fields[1].1], "eventType")?;
    u16::try_from(event_type_value)
        .map_err(|_| JournalError::Format("eventType exceeds u16".to_owned()))?;
    Ok(event_id)
}

fn start_failure_fields(payload: &[u8]) -> Result<(&str, &str, u64), JournalError> {
    if payload.len() < 10 {
        return Err(JournalError::Format(
            "SESSION_START_FAILED payload is truncated".to_owned(),
        ));
    }
    let command_id_length = usize::from(u16::from_le_bytes(payload[0..2].try_into().unwrap()));
    let command_id_end = 2_usize
        .checked_add(command_id_length)
        .ok_or_else(|| JournalError::Format("SESSION_START_FAILED command ID length overflows".to_owned()))?;
    let omitted_end = command_id_end
        .checked_add(8)
        .ok_or_else(|| JournalError::Format("SESSION_START_FAILED payload length overflows".to_owned()))?;
    if payload.len() < omitted_end {
        return Err(JournalError::Format(
            "SESSION_START_FAILED payload is truncated".to_owned(),
        ));
    }
    let command_id = std::str::from_utf8(&payload[2..command_id_end])
        .map_err(|_| JournalError::Format("SESSION_START_FAILED command ID is not UTF-8".to_owned()))?;
    let omitted_byte_count = u64::from_le_bytes(payload[command_id_end..omitted_end].try_into().unwrap());
    let diagnostic = std::str::from_utf8(&payload[omitted_end..])
        .map_err(|_| JournalError::Format("SESSION_START_FAILED diagnostic is not UTF-8".to_owned()))?;
    Ok((command_id, diagnostic, omitted_byte_count))
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
        drop(file);
        fs::rename(&temporary, directory.join(METADATA_NAME))?;
        Ok(())
    })();
    if write_result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    write_result
}

#[cfg(unix)]
fn sync_directory(directory: &Path) -> io::Result<()> {
    File::open(directory)?.sync_all()
}

#[cfg(windows)]
fn sync_directory(directory: &Path) -> io::Result<()> {
    use std::os::windows::fs::OpenOptionsExt;

    const FILE_FLAG_BACKUP_SEMANTICS: u32 = 0x0200_0000;
    OpenOptions::new()
        .read(true)
        .write(true)
        .custom_flags(FILE_FLAG_BACKUP_SEMANTICS)
        .open(directory)?
        .sync_all()
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
    use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
    use std::sync::{Arc, Mutex};
    use std::time::Duration;

    #[derive(Clone, Copy, Debug, Eq, PartialEq)]
    enum RetentionFailureSignal {
        AttemptStarted,
        RetryStarted,
    }

    struct FailingRetentionFileSystem {
        target_name: &'static str,
        remaining_failures: Mutex<Option<usize>>,
        signal: Sender<RetentionFailureSignal>,
    }

    struct RecordingCompressionFileSystem {
        file_syncs: AtomicUsize,
        directory_syncs: AtomicUsize,
    }

    struct CountingRecordFileSync {
        calls: AtomicUsize,
    }

    struct FailingOnceRecordFileSync {
        calls: AtomicUsize,
    }

    struct FailingTwiceRecordFileSync {
        calls: AtomicUsize,
    }

    #[derive(Debug, Eq, PartialEq)]
    enum WriterSyncOperation {
        Data,
        Directory(PathBuf),
        Remove(PathBuf),
    }

    struct RecordingWriterFileSync {
        operations: Arc<Mutex<Vec<WriterSyncOperation>>>,
        directory_calls: AtomicUsize,
        failing_directory_calls: Vec<usize>,
    }

    struct ConcurrentDirectoryCreator {
        target: PathBuf,
        directory_calls: AtomicUsize,
    }

    struct FailingDirectoryOnceSync {
        target: PathBuf,
        failed: AtomicBool,
        operations: Arc<Mutex<Vec<WriterSyncOperation>>>,
    }

    impl RecordFileSync for CountingRecordFileSync {
        fn sync_data(&self, file: &File) -> io::Result<()> {
            self.calls.fetch_add(1, Ordering::SeqCst);
            file.sync_data()
        }
    }

    impl RecordFileSync for FailingOnceRecordFileSync {
        fn sync_data(&self, file: &File) -> io::Result<()> {
            if self.calls.fetch_add(1, Ordering::SeqCst) == 0 {
                return Err(io::Error::new(
                    io::ErrorKind::PermissionDenied,
                    "injected durable append sync failure",
                ));
            }
            file.sync_data()
        }
    }

    impl RecordFileSync for FailingTwiceRecordFileSync {
        fn sync_data(&self, _file: &File) -> io::Result<()> {
            let call = self.calls.fetch_add(1, Ordering::SeqCst);
            if call < 2 {
                return Err(io::Error::new(
                    io::ErrorKind::PermissionDenied,
                    "injected record or rollback sync failure",
                ));
            }
            Ok(())
        }
    }

    impl RecordFileSync for RecordingWriterFileSync {
        fn sync_data(&self, file: &File) -> io::Result<()> {
            self.operations.lock().unwrap().push(WriterSyncOperation::Data);
            file.sync_data()
        }

        fn sync_directory(&self, directory: &Path) -> io::Result<()> {
            self.operations
                .lock()
                .unwrap()
                .push(WriterSyncOperation::Directory(directory.to_path_buf()));
            let call = self.directory_calls.fetch_add(1, Ordering::SeqCst) + 1;
            if self.failing_directory_calls.contains(&call) {
                return Err(io::Error::new(
                    io::ErrorKind::PermissionDenied,
                    format!("injected directory sync failure {call}"),
                ));
            }
            File::open(directory)?.sync_all()
        }

        fn remove_file(&self, path: &Path) -> io::Result<()> {
            self.operations
                .lock()
                .unwrap()
                .push(WriterSyncOperation::Remove(path.to_path_buf()));
            fs::remove_file(path)
        }
    }

    impl RecordFileSync for ConcurrentDirectoryCreator {
        fn sync_data(&self, file: &File) -> io::Result<()> {
            file.sync_data()
        }

        fn sync_directory(&self, directory: &Path) -> io::Result<()> {
            if self.directory_calls.fetch_add(1, Ordering::SeqCst) == 0 {
                fs::create_dir(&self.target)?;
            }
            File::open(directory)?.sync_all()
        }
    }

    impl RecordFileSync for FailingDirectoryOnceSync {
        fn sync_data(&self, file: &File) -> io::Result<()> {
            file.sync_data()
        }

        fn sync_directory(&self, directory: &Path) -> io::Result<()> {
            self.operations
                .lock()
                .unwrap()
                .push(WriterSyncOperation::Directory(directory.to_path_buf()));
            if directory == self.target && !self.failed.swap(true, Ordering::SeqCst) {
                return Err(io::Error::new(
                    io::ErrorKind::PermissionDenied,
                    "injected session component parent sync failure",
                ));
            }
            File::open(directory)?.sync_all()
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
                    let _ = self.signal.send(RetentionFailureSignal::RetryStarted);
                }
                return fs::remove_file(path);
            }
            let _ = self
                .signal
                .send(RetentionFailureSignal::AttemptStarted);
            Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "injected retention deletion failure",
            ))
        }
    }

    impl MaintenanceFileSystem for RecordingCompressionFileSystem {
        fn rename(&self, source: &Path, target: &Path) -> io::Result<()> {
            fs::rename(source, target)
        }

        fn remove_file(&self, path: &Path) -> io::Result<()> {
            fs::remove_file(path)
        }

        fn sync_file(&self, path: &Path) -> io::Result<()> {
            self.file_syncs.fetch_add(1, Ordering::SeqCst);
            File::open(path)?.sync_all()
        }

        fn sync_directory(&self, directory: &Path) -> io::Result<()> {
            self.directory_syncs.fetch_add(1, Ordering::SeqCst);
            File::open(directory)?.sync_all()
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

    fn journal_config(segment_max_bytes: u64, journal_max_bytes: u64) -> JournalConfig {
        JournalConfig {
            segment_max_bytes,
            journal_max_bytes,
        }
    }

    fn recording_writer_sync(
        operations: Arc<Mutex<Vec<WriterSyncOperation>>>,
        failing_directory_calls: Vec<usize>,
    ) -> Arc<RecordingWriterFileSync> {
        Arc::new(RecordingWriterFileSync {
            operations,
            directory_calls: AtomicUsize::new(0),
            failing_directory_calls,
        })
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

    fn command_accepted_payload(operation_sequence: u64, envelope: &[u8]) -> Vec<u8> {
        [operation_sequence.to_le_bytes().as_slice(), envelope].concat()
    }

    fn command_result_payload(
        operation_sequence: u64,
        command_id: &[u8],
        outcome: protocol::CommandOutcome,
        detail: &[u8],
    ) -> Vec<u8> {
        let mut payload = Vec::new();
        payload.extend_from_slice(&operation_sequence.to_le_bytes());
        payload.extend_from_slice(&(command_id.len() as u16).to_le_bytes());
        payload.extend_from_slice(command_id);
        payload.push(outcome.wire_code() as u8);
        payload.extend_from_slice(detail);
        payload
    }

    #[test]
    fn retention_requires_an_acknowledged_event_watermark() {
        let segments = [
            SegmentSize::closed(1, 10, 40),
            SegmentSize::closed(2, 20, 40),
            SegmentSize::active(3, 30, 40),
        ];

        assert!(retention_deletions(&segments, 80, None).unwrap().is_empty());
        assert!(retention_deletions(&segments, 80, Some(9)).unwrap().is_empty());
    }

    #[test]
    fn retention_deletes_only_the_acknowledged_prefix_needed_for_the_limit() {
        let segments = [
            SegmentSize::closed(1, 10, 40),
            SegmentSize::closed(2, 20, 40),
            SegmentSize::active(3, 30, 40),
        ];

        assert_eq!(retention_deletions(&segments, 80, Some(10)).unwrap(), [1]);
        assert_eq!(retention_deletions(&segments, 20, Some(10)).unwrap(), [1]);
        assert_eq!(retention_deletions(&segments, 20, Some(20)).unwrap(), [1, 2]);
    }

    #[test]
    fn retention_keeps_every_segment_when_the_journal_is_within_the_limit() {
        let segments = [
            SegmentSize::closed(1, 10, 40),
            SegmentSize::closed(2, 20, 40),
            SegmentSize::active(3, 30, 40),
        ];

        assert!(retention_deletions(&segments, 120, Some(20)).unwrap().is_empty());
    }

    #[test]
    fn retention_reports_physical_size_overflow() {
        let segments = [
            SegmentSize::closed(1, 10, u64::MAX),
            SegmentSize::active(2, 20, 1),
        ];

        assert!(matches!(
            retention_deletions(&segments, u64::MAX, None),
            Err(JournalError::Maintenance(message))
                if message == "physical journal size overflow"
        ));
    }

    #[test]
    fn journal_config_defaults_to_approved_size_limits() {
        let config = JournalConfig::default();

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
            JournalWriter::create(&directory, journal_config(limit, 1024)).unwrap();

        writer
            .append_at_for_test(1, protocol::event_type::PTY_OUTPUT, b"one")
            .unwrap();
        writer
            .append_at_for_test(2, protocol::event_type::PTY_OUTPUT, b"two")
            .unwrap();
        writer.finish_maintenance().unwrap();

        assert_eq!(decode_compressed_segment(&directory, 1), first);
        assert_eq!(fs::read(directory.join("00000002.cbor")).unwrap(), second);
        assert_eq!(writer.active_segment_number(), 2);
        let scans = scan_segments(&discover_segments(&directory).unwrap()).unwrap();
        assert_eq!(scans.first().unwrap().first_event_id, Some(1));
        assert_eq!(scans.last().unwrap().last_event_id, 2);
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn compresses_every_closed_segment_and_leaves_the_active_segment_raw() {
        let directory = temporary_directory("background-compression");
        let encoded = encode_event(1, protocol::event_type::PTY_OUTPUT, b"same-size").unwrap();
        let limit = u64::try_from(encoded.len()).unwrap();
        let mut writer =
            JournalWriter::create(&directory, journal_config(limit, 1024)).unwrap();

        for event_id in 1..=3 {
            writer
                .append_at_for_test(event_id, protocol::event_type::PTY_OUTPUT, b"same-size")
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
    fn buffered_journal_durably_publishes_compressed_replacements() {
        let directory = temporary_directory("durable-buffered-compression");
        write_raw_event(&directory, 1, 1, b"closed");
        write_raw_event(&directory, 2, 2, b"active");
        let file_system = Arc::new(RecordingCompressionFileSystem {
            file_syncs: AtomicUsize::new(0),
            directory_syncs: AtomicUsize::new(0),
        });
        reconcile_journal(
            &directory,
            2,
            u64::MAX,
            None,
            file_system.as_ref(),
        )
        .unwrap();

        assert!(compressed_segment_path(&directory, 1).is_file());
        assert!(!segment_path(&directory, 1).exists());
        assert_eq!(file_system.file_syncs.load(Ordering::SeqCst), 1);
        assert_eq!(file_system.directory_syncs.load(Ordering::SeqCst), 2);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn compression_does_not_delete_closed_segments_without_acknowledgement() {
        let directory = temporary_directory("compression-without-acknowledgement");
        let encoded = encode_event(1, protocol::event_type::PTY_OUTPUT, b"same-size").unwrap();
        let limit = u64::try_from(encoded.len()).unwrap();
        let mut writer =
            JournalWriter::create(&directory, journal_config(limit, limit)).unwrap();

        for event_id in 1..=3 {
            writer
                .append_at_for_test(event_id, protocol::event_type::PTY_OUTPUT, b"same-size")
                .unwrap();
        }
        writer.finish_maintenance().unwrap();

        for number in 1..=2 {
            assert!(compressed_segment_path(&directory, number).is_file());
        }
        assert!(segment_path(&directory, 3).is_file());
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn rejects_append_after_maintenance_has_finished_without_mutating_the_writer() {
        let directory = temporary_directory("append-after-maintenance");
        let encoded = encode_event(1, protocol::event_type::PTY_OUTPUT, b"same-size").unwrap();
        let limit = u64::try_from(encoded.len()).unwrap();
        let mut writer =
            JournalWriter::create(&directory, journal_config(limit, 1024)).unwrap();
        writer
            .append_at_for_test(1, protocol::event_type::PTY_OUTPUT, b"same-size")
            .unwrap();
        writer.finish_maintenance().unwrap();

        let error = writer
            .append_at_for_test(2, protocol::event_type::PTY_OUTPUT, b"same-size")
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
            JournalWriter::create(&directory, JournalConfig::default()).unwrap();
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
            JournalWriter::create(&directory, journal_config(limit, 1024)).unwrap();

        for (index, payload) in payloads.iter().enumerate() {
            writer
                .append_at_for_test(
                    u64::try_from(index + 1).unwrap(),
                    protocol::event_type::PTY_OUTPUT,
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
                JournalWriter::create(&directory, journal_config(limit, 64 * 1024)).unwrap();

            for event_id in 1..=4 {
                writer
                    .append_at_for_test(event_id, protocol::event_type::PTY_OUTPUT, &payload)
                    .unwrap();
            }
            writer.finish_maintenance().unwrap();

            let scans = scan_segments(&discover_segments(&directory).unwrap()).unwrap();
            assert_eq!(scans.first().unwrap().first_event_id, Some(1));
            assert_eq!(scans.last().unwrap().last_event_id, 4);
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
            journal_config(segment_max_bytes, journal_max_bytes),
        )
        .unwrap();

        for event_id in 1..=5 {
            writer
                .append_at_for_test(event_id, protocol::event_type::PTY_OUTPUT, &payload)
                .unwrap();
        }
        writer.apply_retention_through(3).unwrap();
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
        let scans = scan_segments(&segments).unwrap();
        assert_eq!(scans.first().unwrap().first_event_id, Some(4));
        assert_eq!(scans.last().unwrap().last_event_id, 5);
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn retention_never_deletes_an_oversized_active_segment() {
        let directory = temporary_directory("oversized-active-retention");
        let payload = vec![0x5a; 4096];
        let mut writer =
            JournalWriter::create(&directory, journal_config(1, 1)).unwrap();

        writer
            .append_at_for_test(1, protocol::event_type::PTY_OUTPUT, &payload)
            .unwrap();
        writer.finish_maintenance().unwrap();

        let active = segment_path(&directory, 1);
        assert!(active.is_file());
        assert!(fs::metadata(active).unwrap().len() > 1);
        let scans = scan_segments(&discover_segments(&directory).unwrap()).unwrap();
        assert_eq!(scans[0].first_event_id, Some(1));
        assert_eq!(scans[0].last_event_id, 1);
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn repeated_watermark_retries_a_transient_retention_deletion() {
        let directory = temporary_directory("retention-deletion-retry");
        write_raw_event(&directory, 1, 1, b"closed");
        write_raw_event(&directory, 2, 2, b"active");
        let (signal_sender, signal_receiver) = mpsc::channel();
        let file_system = Arc::new(FailingRetentionFileSystem {
            target_name: "00000001.cbor.zst",
            remaining_failures: Mutex::new(Some(1)),
            signal: signal_sender,
        });
        let mut maintenance = JournalMaintenance::start_with_file_system(
            directory.clone(),
            2,
            1,
            file_system,
        )
        .unwrap();

        let error = maintenance.apply_retention_through(2, 1).unwrap_err();

        assert_eq!(
            receive_retention_signal(&signal_receiver),
            RetentionFailureSignal::AttemptStarted
        );
        assert!(matches!(
            error,
            JournalError::Io(error) if error.kind() == io::ErrorKind::PermissionDenied
        ));
        assert!(compressed_segment_path(&directory, 1).is_file());
        assert!(!segment_path(&directory, 1).exists());

        maintenance.apply_retention_through(2, 1).unwrap();
        assert_eq!(
            receive_retention_signal(&signal_receiver),
            RetentionFailureSignal::RetryStarted
        );
        maintenance.finish(2).unwrap();

        assert!(!compressed_segment_path(&directory, 1).exists());
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn finish_surfaces_a_persistent_acknowledged_retention_failure() {
        let directory = temporary_directory("persistent-retention-deletion-failure");
        write_raw_event(&directory, 1, 1, b"closed");
        write_raw_event(&directory, 2, 2, b"active");
        let (signal_sender, signal_receiver) = mpsc::channel();
        let file_system = Arc::new(FailingRetentionFileSystem {
            target_name: "00000001.cbor.zst",
            remaining_failures: Mutex::new(None),
            signal: signal_sender,
        });
        let mut maintenance = JournalMaintenance::start_with_file_system(
            directory.clone(),
            2,
            1,
            file_system,
        )
        .unwrap();

        let first_error = maintenance.apply_retention_through(2, 1).unwrap_err();
        assert_eq!(
            receive_retention_signal(&signal_receiver),
            RetentionFailureSignal::AttemptStarted
        );
        assert!(matches!(
            first_error,
            JournalError::Io(error) if error.kind() == io::ErrorKind::PermissionDenied
        ));
        let error = maintenance.finish(2).unwrap_err();

        assert!(matches!(
            error,
            JournalError::Io(error) if error.kind() == io::ErrorKind::PermissionDenied
        ));
        assert!(compressed_segment_path(&directory, 1).is_file());
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn reconciliation_compresses_existing_closed_raw_segments() {
        let directory = temporary_directory("recovery-compression");
        for event_id in 1..=3 {
            let encoded = encode_event(event_id, protocol::event_type::PTY_OUTPUT, b"payload").unwrap();
            fs::write(directory.join(format!("{event_id:08}.cbor")), encoded).unwrap();
        }

        reconcile_journal(&directory, 3, 4096, None, &RealMaintenanceFileSystem).unwrap();

        assert!(directory.join("00000001.cbor.zst").is_file());
        assert!(directory.join("00000002.cbor.zst").is_file());
        assert!(directory.join("00000003.cbor").is_file());
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn reconciliation_replaces_an_abandoned_compression_temporary_file() {
        let directory = temporary_directory("recovery-abandoned-compression");
        let closed = write_raw_event(&directory, 1, 1, b"closed");
        let active = write_raw_event(&directory, 2, 2, b"active");
        fs::write(compressed_temporary_segment_path(&directory, 1), b"abandoned").unwrap();
        fs::write(compressed_temporary_segment_path(&directory, 99), b"temporary-only").unwrap();

        let discovered = discover_segments(&directory).unwrap();
        assert_eq!(discovered.iter().map(|segment| segment.number).collect::<Vec<_>>(), [1, 2]);
        let before = scan_segments(&discovered).unwrap();
        assert_eq!(before.first().unwrap().first_event_id, Some(1));
        assert_eq!(before.last().unwrap().last_event_id, 2);

        reconcile_journal(&directory, 2, 4096, None, &RealMaintenanceFileSystem).unwrap();

        assert!(!segment_path(&directory, 1).exists());
        assert!(!compressed_temporary_segment_path(&directory, 1).exists());
        assert!(compressed_temporary_segment_path(&directory, 99).is_file());
        assert_eq!(decode_compressed_segment(&directory, 1), closed);
        assert_eq!(fs::read(segment_path(&directory, 2)).unwrap(), active);
        assert!(!compressed_segment_path(&directory, 2).exists());
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn reconciliation_keeps_an_equal_published_compression_and_removes_the_raw_copy() {
        let directory = temporary_directory("recovery-equal-compression");
        let closed = write_raw_event(&directory, 1, 1, b"closed");
        let published = write_compressed_bytes(&directory, 1, &closed);
        write_raw_event(&directory, 2, 2, b"active");

        let discovered = discover_segments(&directory).unwrap();
        assert_eq!(discovered[0].path, segment_path(&directory, 1));
        assert!(!discovered[0].compressed);
        let before = scan_segments(&discovered).unwrap();
        assert_eq!(before.first().unwrap().first_event_id, Some(1));
        assert_eq!(before.last().unwrap().last_event_id, 2);

        reconcile_journal(&directory, 2, 4096, None, &RealMaintenanceFileSystem).unwrap();

        assert_eq!(fs::read(compressed_segment_path(&directory, 1)).unwrap(), published);
        assert!(!segment_path(&directory, 1).exists());
        assert!(segment_path(&directory, 2).is_file());
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn reconciliation_rebuilds_a_corrupt_published_compression_from_the_raw_copy() {
        let directory = temporary_directory("recovery-corrupt-compression");
        let closed = write_raw_event(&directory, 1, 1, b"closed");
        fs::write(compressed_segment_path(&directory, 1), b"not-zstd").unwrap();
        write_raw_event(&directory, 2, 2, b"active");

        let before = scan_segments(&discover_segments(&directory).unwrap()).unwrap();
        assert_eq!(before.first().unwrap().first_event_id, Some(1));
        assert_eq!(before.last().unwrap().last_event_id, 2);

        reconcile_journal(&directory, 2, 4096, None, &RealMaintenanceFileSystem).unwrap();

        assert_eq!(decode_compressed_segment(&directory, 1), closed);
        assert!(!segment_path(&directory, 1).exists());
        assert!(segment_path(&directory, 2).is_file());
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn reconciliation_rebuilds_a_different_published_compression_from_the_raw_copy() {
        let directory = temporary_directory("recovery-different-compression");
        let closed = write_raw_event(&directory, 1, 1, b"authoritative");
        let different = encode_event(1, protocol::event_type::PTY_OUTPUT, b"different").unwrap();
        assert_ne!(different, closed);
        write_compressed_bytes(&directory, 1, &different);
        write_raw_event(&directory, 2, 2, b"active");

        let before = scan_segments(&discover_segments(&directory).unwrap()).unwrap();
        assert_eq!(before.first().unwrap().first_event_id, Some(1));
        assert_eq!(before.last().unwrap().last_event_id, 2);

        reconcile_journal(&directory, 2, 4096, None, &RealMaintenanceFileSystem).unwrap();

        assert_eq!(decode_compressed_segment(&directory, 1), closed);
        assert!(!segment_path(&directory, 1).exists());
        assert!(segment_path(&directory, 2).is_file());
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

        reconcile_journal(&directory, 3, 4096, None, &RealMaintenanceFileSystem).unwrap();

        assert_eq!(decode_compressed_segment(&directory, 1), first);
        assert_eq!(decode_compressed_segment(&directory, 2), second);
        assert_eq!(fs::read(segment_path(&directory, 3)).unwrap(), third);
        let scans = scan_segments(&discover_segments(&directory).unwrap()).unwrap();
        assert_eq!(scans.first().unwrap().first_event_id, Some(1));
        assert_eq!(scans.last().unwrap().last_event_id, 3);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn does_not_rotate_when_an_item_exactly_reaches_the_segment_limit() {
        let directory = temporary_directory("exact-segment-limit");
        let first = encode_event(1, protocol::event_type::PTY_OUTPUT, b"one").unwrap();
        let second = encode_event(2, protocol::event_type::PTY_OUTPUT, b"two").unwrap();
        let limit = u64::try_from(first.len() + second.len()).unwrap();
        let mut writer =
            JournalWriter::create(&directory, journal_config(limit, 1024)).unwrap();

        writer
            .append_at_for_test(1, protocol::event_type::PTY_OUTPUT, b"one")
            .unwrap();
        writer
            .append_at_for_test(2, protocol::event_type::PTY_OUTPUT, b"two")
            .unwrap();
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
            JournalWriter::create(&directory, journal_config(limit, 1024)).unwrap();

        writer
            .append_at_for_test(1, protocol::event_type::PTY_OUTPUT, b"whole")
            .unwrap();
        writer
            .append_at_for_test(2, protocol::event_type::PTY_OUTPUT, b"next")
            .unwrap();
        writer.finish_maintenance().unwrap();

        assert_eq!(decode_compressed_segment(&directory, 1), first);
        assert_eq!(fs::read(directory.join("00000002.cbor")).unwrap(), second);
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn create_rejects_invalid_journal_limits() {
        for (segment_max_bytes, journal_max_bytes) in [(0, 1024), (1024, 1023)] {
            let directory = temporary_directory("invalid-create-limits");
            let result = JournalWriter::create(
                &directory,
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
        let mut writer = JournalWriter::create(&directory, JournalConfig::default()).unwrap();
        let first = writer
            .append_at_for_test(0, protocol::event_type::PTY_OUTPUT, &[0, 0x1b, 0xff])
            .unwrap();
        writer.rotate().unwrap();
        let second = writer
            .append_at_for_test(0, protocol::event_type::PTY_RESIZE, &protocol::pty_resize_payload(180, 50))
            .unwrap();
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
    fn journal_writer_matches_the_shared_golden_sequence() {
        let directory = temporary_directory("writer-golden");
        let input_id = [
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d,
            0x0e, 0x0f,
        ];
        let mut writer = JournalWriter::create(&directory, JournalConfig::default()).unwrap();
        writer
            .append_at_for_test(1, protocol::event_type::PTY_OUTPUT, &[0, 0x1b, 0xff])
            .unwrap();
        writer
            .append_at_for_test(2, protocol::event_type::PTY_RESIZE, &protocol::pty_resize_payload(180, 50))
            .unwrap();
        writer
            .append_at_for_test(
                3,
                protocol::event_type::PTY_INPUT,
                &protocol::pty_input_payload(input_id, &[0, 0xff]).unwrap(),
            )
            .unwrap();
        writer
            .append_at_for_test(
                4,
                protocol::event_type::PROCESS_EXITED,
                &protocol::process_exited_payload(0, -1),
            )
            .unwrap();
        let expected = hex_bytes(include_str!("../protocol/fixtures/session-events-v1.hex"));
        assert_eq!(fs::read(directory.join("00000001.cbor")).unwrap(), expected);
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn command_records_encode_the_expected_sequence() {
        let directory = temporary_directory("command-records");
        let mut writer = JournalWriter::create(&directory, JournalConfig::default()).unwrap();
        let sequence = u64::MAX;
        let envelope = hex_bytes(concat!(
            "8619810167636f6d6d616e646773657373696f6e",
            "50707172737475767778797a7b7c7d7e7f410066667574757265",
        ));
        let accepted = command_accepted_payload(sequence, &envelope);
        writer
            .append_at_for_test(1, protocol::event_type::COMMAND_ACCEPTED, &accepted)
            .unwrap();
        let cases = [
            (protocol::CommandOutcome::Succeeded, ""),
            (protocol::CommandOutcome::Failed, "effect failed"),
            (protocol::CommandOutcome::Rejected, "rejected"),
            (protocol::CommandOutcome::Ambiguous, "ambiguous"),
        ];
        let mut expected_records = vec![
            protocol::encode_command_accepted(1, sequence, &envelope).unwrap(),
        ];
        for (index, (outcome, detail)) in cases.into_iter().enumerate() {
            let command_id = format!("command.{index}");
            let payload = command_result_payload(sequence, command_id.as_bytes(), outcome, detail.as_bytes());
            let event_id = 2 + index as u64;
            writer
                .append_at_for_test(
                    event_id,
                    protocol::event_type::COMMAND_RESULT,
                    &payload,
                )
                .unwrap();
            expected_records.push(
                protocol::encode_command_result(
                    event_id,
                    sequence,
                    command_id.as_bytes(),
                    outcome,
                    detail,
                )
                .unwrap(),
            );
        }
        assert_eq!(
            fs::read(directory.join("00000001.cbor")).unwrap(),
            expected_records.concat(),
        );
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn session_start_failure_encodes_the_expected_record() {
        let directory = temporary_directory("session-start-failure");
        let mut writer = JournalWriter::create(&directory, JournalConfig::default()).unwrap();
        let payload = protocol::session_start_failed_payload("command.start", "exec failed", 17).unwrap();
        writer
            .append_at_for_test(
                1,
                protocol::event_type::SESSION_START_FAILED,
                &payload,
            )
            .unwrap();
        assert_eq!(
            fs::read(directory.join("00000001.cbor")).unwrap(),
            protocol::encode_session_start_failed(1, "command.start", "exec failed", 17).unwrap()
        );
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn command_records_reject_malformed_payloads_on_write() {
        let directory = temporary_directory("invalid-command-records");
        let mut writer = JournalWriter::create(&directory, JournalConfig::default()).unwrap();
        let invalid_writes = [
            (protocol::event_type::COMMAND_ACCEPTED, vec![0_u8; 7]),
            (
                protocol::event_type::COMMAND_ACCEPTED,
                command_accepted_payload(0, &[0x80]),
            ),
            (
                protocol::event_type::COMMAND_ACCEPTED,
                command_accepted_payload(1, &[]),
            ),
            (protocol::event_type::COMMAND_RESULT, vec![0_u8; 10]),
            (
                protocol::event_type::COMMAND_RESULT,
                command_result_payload(1, b"bad/id", protocol::CommandOutcome::Failed, b"failed"),
            ),
        ];
        for (event_type, payload) in invalid_writes {
            assert!(writer.append_at_for_test(1, event_type, &payload).is_err());
        }
        let unknown_outcome = command_result_payload(1, b"command", protocol::CommandOutcome::Failed, b"");
        let mut unknown_outcome = unknown_outcome;
        unknown_outcome[8 + 2 + b"command".len()] = 5;
        assert!(writer
            .append_at_for_test(1, protocol::event_type::COMMAND_RESULT, &unknown_outcome)
            .is_err());
        let invalid_utf8 = command_result_payload(
            1,
            b"command",
            protocol::CommandOutcome::Failed,
            &[0xff],
        );
        assert!(writer
            .append_at_for_test(1, protocol::event_type::COMMAND_RESULT, &invalid_utf8)
            .is_err());
        let oversized_detail = command_result_payload(
            1,
            b"command",
            protocol::CommandOutcome::Failed,
            &vec![b'x'; 4097],
        );
        assert!(writer
            .append_at_for_test(1, protocol::event_type::COMMAND_RESULT, &oversized_detail)
            .is_err());
        assert!(fs::read(directory.join("00000001.cbor")).unwrap().is_empty());

        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn durable_append_syncs_command_records() {
        let directory = temporary_directory("durable-command-sync");
        let sync = Arc::new(CountingRecordFileSync {
            calls: AtomicUsize::new(0),
        });
        let mut writer = JournalWriter::create_with_record_file_sync(
            &directory,
            JournalConfig::default(),
            sync.clone(),
        )
        .unwrap();
        writer
            .append_buffered(protocol::event_type::PTY_OUTPUT, b"buffered")
            .unwrap();
        assert_eq!(sync.calls.load(Ordering::SeqCst), 0);

        writer
            .append_durable(
                protocol::event_type::COMMAND_ACCEPTED,
                &command_accepted_payload(1, &[0x80]),
            )
            .unwrap();

        assert_eq!(sync.calls.load(Ordering::SeqCst), 1);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn buffered_append_does_not_sync_a_record_below_the_rotation_limit() {
        let directory = temporary_directory("explicit-buffered-append");
        let sync = Arc::new(CountingRecordFileSync {
            calls: AtomicUsize::new(0),
        });
        let mut writer = JournalWriter::create_with_record_file_sync(
            &directory,
            JournalConfig::default(),
            sync.clone(),
        )
        .unwrap();

        writer
            .append_buffered(protocol::event_type::PTY_OUTPUT, b"buffered")
            .unwrap();

        assert_eq!(sync.calls.load(Ordering::SeqCst), 0);
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn writer_durably_creates_each_missing_session_path_component() {
        let ancestor = temporary_directory("durable-session-path");
        let session_directory = ancestor.join("sessions").join("one");
        let operations = Arc::new(Mutex::new(Vec::new()));
        let sync = recording_writer_sync(operations.clone(), Vec::new());

        let writer = JournalWriter::create_with_record_file_sync(
            &session_directory,
            JournalConfig::default(),
            sync,
        )
        .unwrap();

        assert_eq!(
            operations.lock().unwrap().as_slice(),
            [
                WriterSyncOperation::Directory(ancestor.parent().unwrap().to_path_buf()),
                WriterSyncOperation::Directory(ancestor.clone()),
                WriterSyncOperation::Directory(ancestor.join("sessions")),
                WriterSyncOperation::Directory(session_directory.clone()),
            ]
        );
        drop(writer);
        fs::remove_dir_all(ancestor).unwrap();
    }

    #[test]
    fn retry_revalidates_a_component_left_by_failed_parent_sync() {
        let ancestor = temporary_directory("failed-session-path-publication");
        let session_directory = ancestor.join("sessions").join("one");
        let operations = Arc::new(Mutex::new(Vec::new()));
        let sync = FailingDirectoryOnceSync {
            target: ancestor.clone(),
            failed: AtomicBool::new(false),
            operations: operations.clone(),
        };

        assert!(matches!(
            create_session_directory_with_sync(&session_directory, &sync),
            Err(JournalError::Io(_))
        ));
        assert!(ancestor.join("sessions").is_dir());
        assert!(!session_directory.exists());

        create_session_directory_with_sync(&session_directory, &sync).unwrap();

        assert!(session_directory.is_dir());
        assert_eq!(
            operations.lock().unwrap().as_slice(),
            [
                WriterSyncOperation::Directory(ancestor.parent().unwrap().to_path_buf()),
                WriterSyncOperation::Directory(ancestor.clone()),
                WriterSyncOperation::Directory(ancestor.clone()),
                WriterSyncOperation::Directory(ancestor.join("sessions")),
            ]
        );
        fs::remove_dir_all(ancestor).unwrap();
    }

    #[test]
    fn concurrent_component_creation_is_published_before_success() {
        let ancestor = temporary_directory("concurrent-session-path-creation");
        let session_directory = ancestor.join("session");
        let sync = ConcurrentDirectoryCreator {
            target: session_directory.clone(),
            directory_calls: AtomicUsize::new(0),
        };

        create_session_directory_with_sync(&session_directory, &sync).unwrap();

        assert!(session_directory.is_dir());
        assert_eq!(sync.directory_calls.load(Ordering::SeqCst), 2);
        fs::remove_dir_all(ancestor).unwrap();
    }

    #[test]
    fn failed_successor_publication_cleans_up_and_allows_retry() {
        let directory = temporary_directory("successor-publication-retry");
        let operations = Arc::new(Mutex::new(Vec::new()));
        let sync = recording_writer_sync(operations, vec![3]);
        let mut writer = JournalWriter::create_with_record_file_sync(
            &directory,
            journal_config(1, u64::MAX),
            sync,
        )
        .unwrap();
        let prefix_event_id = writer
            .append_buffered(protocol::event_type::PTY_OUTPUT, b"prefix")
            .unwrap();

        assert!(matches!(
            writer.append_buffered(protocol::event_type::PTY_OUTPUT, b"retry"),
            Err(JournalError::Io(_))
        ));
        assert!(!segment_path(&directory, 2).exists());
        assert_eq!(writer.active_segment_number(), 1);
        assert_eq!(writer.latest_event_id(), Some(prefix_event_id));

        writer
            .append_buffered(protocol::event_type::PTY_OUTPUT, b"retry")
            .unwrap();
        assert_eq!(writer.active_segment_number(), 2);
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn failed_successor_publication_cleanup_poisons_the_writer() {
        let directory = temporary_directory("successor-publication-poison");
        let operations = Arc::new(Mutex::new(Vec::new()));
        let sync = recording_writer_sync(operations, vec![3, 4]);
        let mut writer = JournalWriter::create_with_record_file_sync(
            &directory,
            journal_config(1, u64::MAX),
            sync,
        )
        .unwrap();
        let prefix_event_id = writer
            .append_buffered(protocol::event_type::PTY_OUTPUT, b"prefix")
            .unwrap();

        assert!(matches!(
            writer.append_buffered(protocol::event_type::PTY_OUTPUT, b"rotation"),
            Err(JournalError::Maintenance(_))
        ));
        assert!(!segment_path(&directory, 2).exists());
        assert!(matches!(
            writer.append_buffered(protocol::event_type::PTY_OUTPUT, b"x"),
            Err(JournalError::Maintenance(_))
        ));
        assert!(matches!(writer.finish_durably(0), Err(JournalError::Maintenance(_))));
        assert_eq!(writer.active_segment_number(), 1);
        assert_eq!(writer.latest_event_id(), Some(prefix_event_id));
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn preexisting_successor_poisons_the_writer() {
        let directory = temporary_directory("preexisting-successor");
        let mut writer = JournalWriter::create(
            &directory,
            journal_config(1, u64::MAX),
        )
        .unwrap();
        writer
            .append_buffered(protocol::event_type::PTY_OUTPUT, b"prefix")
            .unwrap();
        File::create(segment_path(&directory, 2)).unwrap();

        assert!(matches!(
            writer.append_buffered(protocol::event_type::PTY_OUTPUT, b"rotation"),
            Err(JournalError::Maintenance(_))
        ));
        assert!(matches!(
            writer.append_buffered(protocol::event_type::PTY_OUTPUT, b"x"),
            Err(JournalError::Maintenance(_))
        ));
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn buffered_rotation_syncs_the_closed_segment_without_syncing_the_new_record() {
        let directory = temporary_directory("buffered-rotation-sync");
        let sync = Arc::new(CountingRecordFileSync {
            calls: AtomicUsize::new(0),
        });
        let mut writer = JournalWriter::create_with_record_file_sync(
            &directory,
            journal_config(1, u64::MAX),
            sync.clone(),
        )
        .unwrap();
        writer
            .append_buffered(protocol::event_type::PTY_OUTPUT, b"first")
            .unwrap();
        assert_eq!(sync.calls.load(Ordering::SeqCst), 0);

        writer
            .append_buffered(protocol::event_type::PTY_OUTPUT, b"second")
            .unwrap();

        assert_eq!(writer.active_segment_number(), 2);
        assert_eq!(sync.calls.load(Ordering::SeqCst), 1);
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn durable_append_publishes_segments_and_syncs_the_complete_prefix_in_order() {
        let directory = temporary_directory("explicit-durable-prefix");
        let operations = Arc::new(Mutex::new(Vec::new()));
        let record_sync = recording_writer_sync(operations.clone(), Vec::new());
        let mut writer = JournalWriter::create_with_file_systems(
            &directory,
            journal_config(1, u64::MAX),
            Arc::new(RealMaintenanceFileSystem),
            record_sync,
        )
        .unwrap();
        writer
            .append_buffered(protocol::event_type::PTY_OUTPUT, b"prefix")
            .unwrap();

        writer
            .append_durable(
                protocol::event_type::COMMAND_ACCEPTED,
                &command_accepted_payload(1, &[0x80]),
            )
            .unwrap();

        assert_eq!(
            operations.lock().unwrap().as_slice(),
            [
                WriterSyncOperation::Directory(directory.parent().unwrap().to_path_buf()),
                WriterSyncOperation::Directory(directory.clone()),
                WriterSyncOperation::Data,
                WriterSyncOperation::Directory(directory.clone()),
                WriterSyncOperation::Data,
            ]
        );
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn finish_durably_writes_and_syncs_the_canonical_process_exit_record() {
        let directory = temporary_directory("durable-finish");
        let sync = Arc::new(CountingRecordFileSync {
            calls: AtomicUsize::new(0),
        });
        let mut writer = JournalWriter::create_with_record_file_sync(
            &directory,
            JournalConfig::default(),
            sync.clone(),
        )
        .unwrap();

        let event_id = writer.finish_durably(37).unwrap();

        assert_eq!(sync.calls.load(Ordering::SeqCst), 1);
        assert_eq!(
            fs::read(segment_path(&directory, 1)).unwrap(),
            protocol::encode_process_exited(event_id, 37)
        );
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn successful_finish_rejects_every_later_record_without_mutation() {
        let directory = temporary_directory("terminal-durable-finish");
        let mut writer = JournalWriter::create(&directory, JournalConfig::default()).unwrap();
        let exit_event_id = writer.finish_durably(37).unwrap();
        let accepted = fs::read(segment_path(&directory, 1)).unwrap();

        assert!(matches!(
            writer.append_buffered(protocol::event_type::PTY_OUTPUT, b"late buffered"),
            Err(JournalError::Finished)
        ));
        assert!(matches!(
            writer.append_durable(
                protocol::event_type::COMMAND_RESULT,
                &command_result_payload(
                    1,
                    b"late-result",
                    protocol::CommandOutcome::Succeeded,
                    b"",
                ),
            ),
            Err(JournalError::Finished)
        ));
        assert!(matches!(writer.finish_durably(38), Err(JournalError::Finished)));
        assert_eq!(writer.latest_event_id(), Some(exit_event_id));
        assert_eq!(fs::read(segment_path(&directory, 1)).unwrap(), accepted);
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn failed_final_sync_removes_the_exit_record_and_allows_a_correct_retry() {
        let directory = temporary_directory("failed-durable-finish-sync");
        let sync = Arc::new(FailingOnceRecordFileSync {
            calls: AtomicUsize::new(0),
        });
        let mut writer = JournalWriter::create_with_record_file_sync(
            &directory,
            JournalConfig::default(),
            sync,
        )
        .unwrap();

        assert!(writer.finish_durably(17).is_err());
        assert!(fs::read(segment_path(&directory, 1)).unwrap().is_empty());

        let event_id = writer.finish_durably(17).unwrap();
        assert_eq!(
            fs::read(segment_path(&directory, 1)).unwrap(),
            protocol::encode_process_exited(event_id, 17)
        );
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn failed_exit_and_rollback_sync_preserve_prefix_and_poison_the_writer() {
        let directory = temporary_directory("poisoned-durable-finish-sync");
        let sync = Arc::new(FailingTwiceRecordFileSync {
            calls: AtomicUsize::new(0),
        });
        let mut writer = JournalWriter::create_with_record_file_sync(
            &directory,
            JournalConfig::default(),
            sync,
        )
        .unwrap();
        writer
            .append_at_for_test(1, protocol::event_type::PTY_OUTPUT, b"preserved prefix")
            .unwrap();
        let prefix = fs::read(segment_path(&directory, 1)).unwrap();

        assert!(matches!(writer.finish_durably(17), Err(JournalError::Maintenance(_))));
        assert_eq!(fs::read(segment_path(&directory, 1)).unwrap(), prefix);
        assert_eq!(writer.latest_event_id(), Some(1));
        assert!(matches!(
            writer.append_buffered(protocol::event_type::PTY_OUTPUT, b"late"),
            Err(JournalError::Maintenance(_))
        ));
        assert!(matches!(writer.finish_durably(17), Err(JournalError::Maintenance(_))));
        assert_eq!(fs::read(segment_path(&directory, 1)).unwrap(), prefix);
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn finish_durably_surfaces_an_exit_append_failure_without_accepting_an_event() {
        let directory = temporary_directory("failed-durable-finish-append");
        let mut writer = JournalWriter::create(&directory, JournalConfig::default()).unwrap();
        writer.previous_event_id = u64::MAX;

        let error = writer.finish_durably(17).unwrap_err();

        assert!(matches!(error, JournalError::EventIdExhausted));
        assert_eq!(writer.latest_event_id(), Some(u64::MAX));
        assert!(fs::read(segment_path(&directory, 1)).unwrap().is_empty());
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn finish_durably_syncs_old_prefix_publishes_new_segment_then_syncs_exit() {
        let directory = temporary_directory("durable-finish-rotation");
        let operations = Arc::new(Mutex::new(Vec::new()));
        let record_sync = recording_writer_sync(operations.clone(), Vec::new());
        let mut writer = JournalWriter::create_with_file_systems(
            &directory,
            journal_config(1, u64::MAX),
            Arc::new(RealMaintenanceFileSystem),
            record_sync,
        )
        .unwrap();
        writer
            .append_buffered(protocol::event_type::PTY_OUTPUT, b"prefix")
            .unwrap();

        let event_id = writer.finish_durably(-9).unwrap();

        assert_eq!(writer.active_segment_number(), 2);
        assert_eq!(
            operations.lock().unwrap().as_slice(),
            [
                WriterSyncOperation::Directory(directory.parent().unwrap().to_path_buf()),
                WriterSyncOperation::Directory(directory.clone()),
                WriterSyncOperation::Data,
                WriterSyncOperation::Directory(directory.clone()),
                WriterSyncOperation::Data,
            ]
        );
        assert_eq!(
            fs::read(segment_path(&directory, 2)).unwrap(),
            protocol::encode_process_exited(event_id, -9)
        );
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn durable_append_syncs_the_closed_prefix_when_it_rotates() {
        let directory = temporary_directory("durable-command-rotation");
        let sync = Arc::new(CountingRecordFileSync {
            calls: AtomicUsize::new(0),
        });
        let mut writer = JournalWriter::create_with_record_file_sync(
            &directory,
            journal_config(1, u64::MAX),
            sync.clone(),
        )
        .unwrap();
        writer
            .append_buffered(protocol::event_type::PTY_OUTPUT, b"buffered prefix")
            .unwrap();

        writer
            .append_durable(
                protocol::event_type::COMMAND_ACCEPTED,
                &command_accepted_payload(1, &[0x80]),
            )
            .unwrap();

        assert_eq!(writer.active_segment_number(), 2);
        assert_eq!(sync.calls.load(Ordering::SeqCst), 2);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn failed_durable_append_removes_the_unaccepted_record_before_retry() {
        let directory = temporary_directory("failed-durable-command-sync");
        let sync = Arc::new(FailingOnceRecordFileSync {
            calls: AtomicUsize::new(0),
        });
        let mut writer = JournalWriter::create_with_record_file_sync(
            &directory,
            JournalConfig::default(),
            sync,
        )
        .unwrap();
        let payload = command_accepted_payload(1, &[0x80]);

        assert!(writer
            .append_durable(protocol::event_type::COMMAND_ACCEPTED, &payload)
            .is_err());
        assert!(fs::read(directory.join("00000001.cbor")).unwrap().is_empty());

        let event_id = writer
            .append_durable(protocol::event_type::COMMAND_ACCEPTED, &payload)
            .unwrap();
        let scans = scan_segments(&discover_segments(&directory).unwrap()).unwrap();
        assert_eq!(scans[0].first_event_id, Some(event_id));
        assert_eq!(scans[0].last_event_id, event_id);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn rejects_invalid_known_payloads_on_write() {
        let directory = temporary_directory("invalid-known-payloads");
        let mut writer = JournalWriter::create(&directory, JournalConfig::default()).unwrap();
        assert!(writer
            .append_at_for_test(1, protocol::event_type::PTY_RESIZE, &protocol::pty_resize_payload(0, 50))
            .is_err());
        assert!(writer
            .append_at_for_test(
                1,
                protocol::event_type::PTY_RESIZE,
                &protocol::pty_resize_payload(65536, 50),
            )
            .is_err());
        assert!(writer
            .append_at_for_test(1, protocol::event_type::PROCESS_STARTED, &0_u64.to_le_bytes())
            .is_err());
        let mut invalid_signal = Vec::new();
        invalid_signal.extend_from_slice(&1_u16.to_le_bytes());
        invalid_signal.extend_from_slice(&0_u16.to_le_bytes());
        invalid_signal.extend_from_slice(&(-2_i32).to_le_bytes());
        assert!(writer
            .append_at_for_test(1, protocol::event_type::SIGNAL, &invalid_signal)
            .is_err());
        assert!(fs::read(directory.join("00000001.cbor")).unwrap().is_empty());
        let delivered_signal = crate::host::signal_payload(1, 0);
        assert_eq!(
            writer
                .append_at_for_test(1, protocol::event_type::SIGNAL, &delivered_signal)
                .unwrap(),
            1,
        );
        assert_eq!(
            fs::read(directory.join("00000001.cbor")).unwrap(),
            protocol::encode_signal(1, 1, 0).unwrap(),
        );
        assert!(protocol::encode_signal(1, 1, 2).is_ok());
        assert!(protocol::encode_signal(1, 1, 0).is_ok());
        assert!(protocol::encode_signal(1, 1, -2).is_err());
        assert!(protocol::encode_signal(1, 6, -1).is_err());
        assert!(protocol::encode_signal(1, 0xffff, -1).is_err());
        drop(writer);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn scanner_ignores_only_a_partial_active_tail() {
        let directory = temporary_directory("scan-cbor-tail");
        let complete = hex_bytes("830719010044676f6f64");
        let partial = hex_bytes("830819010046706172");
        let path = directory.join("00000001.cbor");
        let bytes = [complete.clone(), partial].concat();
        fs::write(&path, &bytes).unwrap();

        let segment = SegmentFile {
            number: 1,
            path,
            compressed: false,
        };
        let scan = scan_path(&segment, ScanExtent::AllRecords, true, 0).unwrap();

        assert_eq!(scan.last_event_id, 7);
        assert!(scan.ignored_crash_tail);
        assert_eq!(fs::read(&segment.path).unwrap(), bytes);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn structural_scan_rejects_event_id_regression_between_segments() {
        let directory = temporary_directory("cross-segment-order");
        write_raw_event(&directory, 1, 2, b"first");
        write_raw_event(&directory, 2, 1, b"second");
        let segments = discover_segments(&directory).unwrap();

        assert!(scan_segments(&segments).is_err());
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn scanner_accepts_an_active_segment_with_only_a_partial_first_item() {
        let directory = temporary_directory("partial-first-item");
        let path = directory.join("00000001.cbor");
        fs::write(&path, hex_bytes("830119010046706172")).unwrap();

        let scan = scan_path(
            &SegmentFile {
                number: 1,
                path,
                compressed: false,
            },
            ScanExtent::AllRecords,
            true,
            0,
        )
        .unwrap();

        assert_eq!(scan.last_event_id, 0);
        assert!(scan.ignored_crash_tail);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn structural_scanner_validates_compressed_closed_segments() {
        let directory = temporary_directory("compressed-segment");
        let closed = [
            hex_bytes("8301190100456669727374"),
            hex_bytes("8302190100467365636f6e64"),
        ]
        .concat();
        let compressed = zstd::stream::encode_all(closed.as_slice(), 1).unwrap();
        fs::write(directory.join("00000007.cbor.zst"), compressed).unwrap();
        fs::write(directory.join("00000008.cbor"), hex_bytes("8303190100457468697264")).unwrap();

        let scans = scan_segments(&discover_segments(&directory).unwrap()).unwrap();
        assert_eq!(scans.len(), 2);
        assert_eq!(scans[0].first_event_id, Some(1));
        assert_eq!(scans[0].last_event_id, 2);
        assert_eq!(scans[1].first_event_id, Some(3));
        assert_eq!(scans[1].last_event_id, 3);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn first_record_scan_stops_before_requesting_more_input() {
        struct FirstRecordThenFail {
            first_record: Option<Vec<u8>>,
            requested_more: bool,
        }

        impl Read for FirstRecordThenFail {
            fn read(&mut self, target: &mut [u8]) -> io::Result<usize> {
                let Some(first_record) = self.first_record.take() else {
                    self.requested_more = true;
                    return Err(io::Error::other("scanner read past the first record"));
                };
                target[..first_record.len()].copy_from_slice(&first_record);
                Ok(first_record.len())
            }
        }

        let mut source = FirstRecordThenFail {
            first_record: Some(hex_bytes("83182a190100456669727374")),
            requested_more: false,
        };

        let scan = scan_reader(&mut source, ScanExtent::FirstRecord, false, 0, None).unwrap();

        assert_eq!(scan.first_event_id, Some(42));
        assert_eq!(scan.last_event_id, 42);
        assert!(!source.requested_more);
    }

    #[test]
    fn first_event_discovery_stops_after_a_compressed_first_record() {
        let directory = temporary_directory("compressed-first-event");
        let bytes = [hex_bytes("8307190100456669727374"), vec![0xff]].concat();
        let compressed = zstd::stream::encode_all(bytes.as_slice(), 1).unwrap();
        fs::write(directory.join("00000001.cbor.zst"), compressed).unwrap();
        fs::write(directory.join("00000002.cbor"), hex_bytes("8308190100467365636f6e64")).unwrap();
        let segments = discover_segments(&directory).unwrap();

        assert!(scan_path(&segments[0], ScanExtent::AllRecords, false, 0).is_err());
        fs::remove_file(&segments[1].path).unwrap();
        assert_eq!(first_available_event_id(&segments).unwrap(), Some(7));
        fs::remove_dir_all(directory).unwrap();
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

        assert!(scan_segments(&discover_segments(&directory).unwrap()).is_err());
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
                unavailable_policy: SandboxUnavailablePolicy::RunUnsandboxed,
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
        write_metadata(&directory, &metadata).unwrap();
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

    #[test]
    fn metadata_reader_accepts_legacy_fail_unavailable_policy() {
        let directory = temporary_directory("legacy-fail-metadata");
        let mut value: serde_json::Value = serde_json::from_str(include_str!(
            "../protocol/fixtures/metadata-v1.json"
        ))
        .unwrap();
        value["sandbox"]["unavailablePolicy"] = serde_json::json!("fail");
        fs::write(directory.join(METADATA_NAME), serde_json::to_vec(&value).unwrap()).unwrap();

        assert_eq!(
            read_metadata(&directory).unwrap().sandbox.unavailable_policy,
            SandboxUnavailablePolicy::Fail
        );
        fs::remove_dir_all(directory).unwrap();
    }
}
