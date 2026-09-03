use std::cmp::max;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::time::{Instant, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};

use crate::protocol::{self, MAX_PAYLOAD_LENGTH};

const METADATA_NAME: &str = "metadata";
const FIRST_SEGMENT: u64 = 1;
const MAX_CBOR_DEPTH: usize = 64;
const MAX_RECORD_FIELDS: usize = 1024;
const MAX_ENCODED_RECORD_LENGTH: usize = MAX_PAYLOAD_LENGTH + 4096;
const MAX_DECOMPRESSED_SEGMENT_LENGTH: u64 = 512 * 1024 * 1024;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Durability {
    Buffered,
    EveryRecord,
}

#[derive(Clone, Debug)]
pub struct JournalConfig {
    pub durability: Durability,
}

impl Default for JournalConfig {
    fn default() -> Self {
        Self {
            durability: Durability::Buffered,
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
    Format(String),
    EventIdExhausted,
    PayloadTooLarge(usize),
}

impl std::fmt::Display for JournalError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Io(error) => write!(formatter, "journal I/O error: {error}"),
            Self::Format(message) => write!(formatter, "invalid journal format: {message}"),
            Self::EventIdExhausted => formatter.write_str("journal event ID is exhausted"),
            Self::PayloadTooLarge(length) => {
                write!(formatter, "journal payload exceeds 16 MiB: {length}")
            }
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
    session_start: Instant,
    config: JournalConfig,
}

impl JournalWriter {
    pub fn create(
        directory: impl AsRef<Path>,
        journal_id: [u8; 16],
        config: JournalConfig,
    ) -> Result<Self, JournalError> {
        let directory = directory.as_ref().to_path_buf();
        fs::create_dir_all(&directory)?;
        let segment_number = FIRST_SEGMENT;
        let file = create_segment(&directory, segment_number, config.durability)?;
        Ok(Self {
            directory,
            file,
            journal_id,
            previous_event_id: 0,
            segment_number,
            session_start: Instant::now(),
            config,
        })
    }

    pub fn recover(
        directory: impl AsRef<Path>,
        journal_id: [u8; 16],
        config: JournalConfig,
    ) -> Result<Self, JournalError> {
        let directory = directory.as_ref().to_path_buf();
        let segments = discover_segments(&directory)?;
        let Some(active) = segments.last() else {
            return Self::create(directory, journal_id, config);
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
        Ok(Self {
            directory,
            file,
            journal_id,
            previous_event_id,
            segment_number: active.number,
            session_start: Instant::now(),
            config,
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
        self.file.write_all(&record)?;
        if self.config.durability == Durability::EveryRecord {
            self.file.sync_data()?;
        }
        self.previous_event_id = event_id;
        Ok(event_id)
    }

    pub fn rotate(&mut self) -> Result<(), JournalError> {
        self.file.flush()?;
        if self.config.durability == Durability::EveryRecord {
            self.file.sync_data()?;
        }
        let number = self
            .segment_number
            .checked_add(1)
            .ok_or_else(|| JournalError::Format("segment number is exhausted".to_owned()))?;
        self.file = create_segment(&self.directory, number, self.config.durability)?;
        self.segment_number = number;
        Ok(())
    }

    pub fn flush(&mut self) -> Result<(), JournalError> {
        self.file.flush()?;
        Ok(())
    }

    pub fn directory(&self) -> &Path {
        &self.directory
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
    let segments = discover_segments(directory.as_ref())?;
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

fn discover_segments(directory: &Path) -> Result<Vec<SegmentFile>, JournalError> {
    let mut segments = Vec::new();
    for entry in fs::read_dir(directory)? {
        let entry = entry?;
        let Some((number, compressed)) = segment_number(&entry.file_name()) else {
            continue;
        };
        segments.push(SegmentFile {
            number,
            path: entry.path(),
            compressed,
        });
    }
    segments.sort_by_key(|segment| segment.number);
    for pair in segments.windows(2) {
        if pair[0].number == pair[1].number {
            return Err(JournalError::Format(
                "both compressed and uncompressed copies exist for a segment".to_owned(),
            ));
        }
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
        let file = File::open(&segment.path)?;
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
        return Ok(bytes);
    }
    Ok(fs::read(&segment.path)?)
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
    pub journal_id: String,
    pub created_at_epoch_millis: u64,
    pub session_start_epoch_millis: u64,
    pub command: Vec<String>,
    pub cwd: String,
    pub host_pid: u64,
    pub child_pid: Option<u64>,
    pub state: SessionState,
    pub initial_cols: u16,
    pub initial_rows: u16,
    pub current_cols: u16,
    pub current_rows: u16,
    pub term: String,
    pub sandbox: SandboxMetadata,
    pub control: ControlMetadata,
    pub active_segment: u64,
    pub oldest_available_event_id: Option<u64>,
    pub latest_event_id: Option<u64>,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum SessionState {
    Starting,
    Running,
    Exited,
    Failed,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SandboxMetadata {
    pub requested: bool,
    pub enforcement: SandboxEnforcement,
    pub unavailable_policy: SandboxUnavailablePolicy,
    pub read_write_paths: Vec<String>,
    pub read_only_paths: Vec<String>,
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
        || !valid_journal_id(&metadata.journal_id)
        || metadata.command.is_empty()
        || metadata.cwd.is_empty()
        || metadata.host_pid == 0
        || metadata.child_pid == Some(0)
        || !valid_dimensions(metadata.initial_cols, metadata.initial_rows)
        || !valid_dimensions(metadata.current_cols, metadata.current_rows)
        || metadata.term.is_empty()
        || metadata.term.len() > 128
        || metadata.active_segment == 0
        || metadata.control.endpoint.is_empty()
    {
        return Err(JournalError::Format("metadata has invalid required fields".to_owned()));
    }
    match (metadata.oldest_available_event_id, metadata.latest_event_id) {
        (None, None) => Ok(()),
        (Some(oldest), Some(latest)) if oldest != 0 && oldest <= latest => Ok(()),
        _ => Err(JournalError::Format("metadata event IDs are invalid".to_owned())),
    }
}

fn valid_session_id(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 128
        && !matches!(value, "." | "..")
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
}

fn valid_journal_id(value: &str) -> bool {
    value.len() == 36
        && value.bytes().enumerate().all(|(index, byte)| {
            if matches!(index, 8 | 13 | 18 | 23) {
                byte == b'-'
            } else {
                byte.is_ascii_digit() || matches!(byte, b'a'..=b'f')
            }
        })
}

fn valid_dimensions(cols: u16, rows: u16) -> bool {
    cols != 0 && rows != 0
}

#[cfg(test)]
mod tests {
    use super::*;

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

        assert_eq!((first, second), (1, 2));
        assert_eq!(fs::read(directory.join("00000001.cbor")).unwrap(), hex_bytes("830119010043001bff"));
        assert_eq!(
            fs::read(directory.join("00000002.cbor")).unwrap(),
            hex_bytes("83021901028218b41832"),
        );
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
    fn rejects_corruption_instead_of_treating_it_as_a_tail() {
        let directory = temporary_directory("corrupt-cbor");
        fs::write(directory.join("00000001.cbor"), [0xff]).unwrap();

        assert!(read_after(&directory, 0).is_err());
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn metadata_round_trips_event_id_bounds() {
        let directory = temporary_directory("metadata");
        let metadata = Metadata {
            metadata_version: 1,
            journal_format_version: 1,
            control_protocol_version: 1,
            session_id: "session-1".to_owned(),
            journal_id: "00010203-0405-0607-0809-0a0b0c0d0e0f".to_owned(),
            created_at_epoch_millis: 1,
            session_start_epoch_millis: 2,
            command: vec!["bash".to_owned(), String::new()],
            cwd: "/work".to_owned(),
            host_pid: 10,
            child_pid: None,
            state: SessionState::Starting,
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
            },
            control: ControlMetadata {
                transport: ControlTransport::UnixDomainSocket,
                endpoint: "control.sock".to_owned(),
            },
            active_segment: 1,
            oldest_available_event_id: Some(7),
            latest_event_id: Some(9),
        };
        write_metadata(&directory, &metadata, Durability::EveryRecord).unwrap();
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

        assert_eq!(read_metadata(&directory).unwrap().state, SessionState::Running);
        fs::remove_dir_all(directory).unwrap();
    }
}
