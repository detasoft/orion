use std::cmp::max;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};
use std::time::{Instant, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};

use crate::protocol::{
    self, block_flags, codec, BlockHeader, Record, BLOCK_HEADER_LENGTH, JOURNAL_VERSION,
    MAX_PAYLOAD_LENGTH, RECORD_HEADER_LENGTH, SEGMENT_HEADER_LENGTH,
};

const METADATA_NAME: &str = "metadata";
const FIRST_SEGMENT: u64 = 1;

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
    pub event_type: u16,
    pub payload_schema_version: u16,
    pub flags: u32,
    pub timestamp: u64,
    pub payload: Vec<u8>,
    pub opaque: bool,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ReadResult {
    pub events: Vec<JournalEvent>,
    pub ignored_crash_tail: bool,
}

#[derive(Debug)]
pub enum JournalError {
    Io(io::Error),
    Format(String),
    TimestampExhausted,
    PayloadTooLarge(usize),
}

impl std::fmt::Display for JournalError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Io(error) => write!(formatter, "journal I/O error: {error}"),
            Self::Format(message) => write!(formatter, "invalid journal format: {message}"),
            Self::TimestampExhausted => formatter.write_str("journal timestamp is exhausted"),
            Self::PayloadTooLarge(length) => write!(formatter, "journal payload exceeds 16 MiB: {length}"),
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
    previous_timestamp: u64,
    next_block_sequence: u64,
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
        let path = segment_path(&directory, FIRST_SEGMENT);
        let mut file = OpenOptions::new().write(true).create_new(true).open(path)?;
        file.write_all(&protocol::encode_segment_header(
            journal_id,
            FIRST_SEGMENT,
            epoch_millis()?,
        ))?;
        if config.durability == Durability::EveryRecord {
            file.sync_data()?;
            sync_directory(&directory)?;
        }
        Ok(Self {
            directory,
            file,
            journal_id,
            previous_timestamp: 0,
            next_block_sequence: 0,
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
        let raw_timestamp = u64::try_from(elapsed).unwrap_or(u64::MAX);
        self.append_at(raw_timestamp, event_type, payload_schema_version, flags, payload)
    }

    pub fn append_at(
        &mut self,
        raw_timestamp: u64,
        event_type: u16,
        payload_schema_version: u16,
        flags: u32,
        payload: &[u8],
    ) -> Result<u64, JournalError> {
        if payload.len() > MAX_PAYLOAD_LENGTH {
            return Err(JournalError::PayloadTooLarge(payload.len()));
        }
        let next = self
            .previous_timestamp
            .checked_add(1)
            .ok_or(JournalError::TimestampExhausted)?;
        let timestamp = max(raw_timestamp, next);
        let record = protocol::encode_record(Record {
            event_type,
            payload_schema_version,
            flags,
            timestamp,
            payload,
        })
        .map_err(|_| JournalError::PayloadTooLarge(payload.len()))?;
        let block = protocol::encode_block_header(BlockHeader {
            codec: codec::NONE,
            flags: block_flags::FINAL,
            block_sequence: self.next_block_sequence,
            first_timestamp: timestamp,
            last_timestamp: timestamp,
            uncompressed_length: record.len() as u32,
            stored_length: record.len() as u32,
            record_count: 1,
            payload_crc32c: protocol::crc32c(&record),
        });
        self.file.write_all(&block)?;
        self.file.write_all(&record)?;
        if self.config.durability == Durability::EveryRecord {
            self.file.sync_data()?;
        }
        self.previous_timestamp = timestamp;
        self.next_block_sequence += 1;
        Ok(timestamp)
    }

    pub fn flush(&mut self) -> Result<(), JournalError> {
        self.file.flush()?;
        Ok(())
    }

    pub fn directory(&self) -> &Path {
        &self.directory
    }
}

pub fn read(directory: impl AsRef<Path>, cursor: u64) -> Result<ReadResult, JournalError> {
    let directory = directory.as_ref();
    let mut segments = Vec::new();
    for entry in fs::read_dir(directory)? {
        let entry = entry?;
        let Some(sequence) = segment_sequence(&entry.file_name()) else {
            continue;
        };
        segments.push((sequence, entry.path()));
    }
    segments.sort_by_key(|(sequence, _)| *sequence);

    let mut result = ReadResult {
        events: Vec::new(),
        ignored_crash_tail: false,
    };
    let mut expected_sequence = FIRST_SEGMENT;
    let mut journal_id = None;
    let mut previous_timestamp = 0;
    let segment_count = segments.len();
    for (index, (sequence, path)) in segments.into_iter().enumerate() {
        if sequence != expected_sequence {
            return Err(JournalError::Format("segment sequence has a gap".to_owned()));
        }
        expected_sequence += 1;
        let mut bytes = Vec::new();
        File::open(path)?.read_to_end(&mut bytes)?;
        let segment = read_segment(
            &bytes,
            sequence,
            cursor,
            journal_id,
            previous_timestamp,
        )?;
        if segment.ignored_crash_tail && index + 1 != segment_count {
            return Err(JournalError::Format(
                "crash tail in a non-final segment".to_owned(),
            ));
        }
        journal_id = Some(segment.journal_id);
        previous_timestamp = segment.last_timestamp;
        result.events.extend(segment.events);
        result.ignored_crash_tail |= segment.ignored_crash_tail;
    }
    Ok(result)
}

struct SegmentRead {
    journal_id: [u8; 16],
    events: Vec<JournalEvent>,
    ignored_crash_tail: bool,
    last_timestamp: u64,
}

fn read_segment(
    bytes: &[u8],
    expected_sequence: u64,
    cursor: u64,
    expected_journal_id: Option<[u8; 16]>,
    initial_timestamp: u64,
) -> Result<SegmentRead, JournalError> {
    if bytes.len() < SEGMENT_HEADER_LENGTH {
        return Err(JournalError::Format("truncated segment header".to_owned()));
    }
    let header = &bytes[..SEGMENT_HEADER_LENGTH];
    if &header[..8] != protocol::SEGMENT_MAGIC {
        return Err(JournalError::Format("bad segment magic".to_owned()));
    }
    let header_length = u16_at(&header[10..12]) as usize;
    if u16_at(&header[8..10]) != JOURNAL_VERSION || header_length < SEGMENT_HEADER_LENGTH {
        return Err(JournalError::Format("unsupported segment header".to_owned()));
    }
    if bytes.len() < header_length {
        return Err(JournalError::Format("truncated segment header".to_owned()));
    }
    if u32_at(&header[12..16]) != 0 || u32_at(&header[60..64]) != protocol::crc32c(&header[..60]) {
        return Err(JournalError::Format("bad segment header checksum or flags".to_owned()));
    }
    if u64_at(&header[32..40]) != expected_sequence {
        return Err(JournalError::Format("unexpected segment sequence".to_owned()));
    }
    let mut journal_id = [0; 16];
    journal_id.copy_from_slice(&header[16..32]);
    if expected_journal_id.is_some_and(|id| id != journal_id) {
        return Err(JournalError::Format("journal ID changes between segments".to_owned()));
    }

    let mut position = header_length;
    let mut expected_block = 0;
    let mut previous_timestamp = initial_timestamp;
    let mut events = Vec::new();
    let mut ignored_crash_tail = false;
    while position < bytes.len() {
        let remaining = &bytes[position..];
        if remaining.len() < BLOCK_HEADER_LENGTH {
            ignored_crash_tail = true;
            break;
        }
        let block = parse_block_header(&remaining[..BLOCK_HEADER_LENGTH])?;
        if block.block_sequence != expected_block {
            return Err(JournalError::Format("unexpected block sequence".to_owned()));
        }
        expected_block += 1;
        let payload_start = position + BLOCK_HEADER_LENGTH;
        let available = bytes.len() - payload_start;
        let declared = block.stored_length as usize;
        if available < declared {
            match block.codec {
                codec::NONE => {
                    let (mut recovered, _tail, last_timestamp) = parse_records(
                        &bytes[payload_start..],
                        cursor,
                        true,
                        previous_timestamp,
                    )?;
                    previous_timestamp = last_timestamp;
                    events.append(&mut recovered);
                    ignored_crash_tail = true;
                    break;
                }
                codec::ZSTD => {
                    ignored_crash_tail = true;
                    break;
                }
                _ => return Err(JournalError::Format("unsupported block codec".to_owned())),
            }
        }
        let payload = &bytes[payload_start..payload_start + declared];
        if protocol::crc32c(payload) != block.payload_crc32c {
            return Err(JournalError::Format("bad block payload checksum".to_owned()));
        }
        if block.codec != codec::NONE {
            return Err(JournalError::Format("unsupported block codec".to_owned()));
        }
        if block.uncompressed_length as usize != payload.len() {
            return Err(JournalError::Format("invalid uncompressed block length".to_owned()));
        }
        let (mut decoded, tail, _) = parse_records(payload, cursor, false, previous_timestamp)?;
        if tail || decoded.len() > block.record_count as usize {
            return Err(JournalError::Format("incomplete record in complete block".to_owned()));
        }
        let all_records = count_records(payload, previous_timestamp)?;
        if all_records.len() != block.record_count as usize
            || all_records.first().map(|event| event.timestamp) != Some(block.first_timestamp)
            || all_records.last().map(|event| event.timestamp) != Some(block.last_timestamp)
        {
            return Err(JournalError::Format("block record metadata does not match payload".to_owned()));
        }
        previous_timestamp = block.last_timestamp;
        events.append(&mut decoded);
        position = payload_start + declared;
    }
    Ok(SegmentRead {
        journal_id,
        events,
        ignored_crash_tail,
        last_timestamp: previous_timestamp,
    })
}

#[derive(Clone, Copy)]
struct ParsedBlock {
    codec: u16,
    block_sequence: u64,
    first_timestamp: u64,
    last_timestamp: u64,
    uncompressed_length: u32,
    stored_length: u32,
    record_count: u32,
    payload_crc32c: u32,
}

fn parse_block_header(header: &[u8]) -> Result<ParsedBlock, JournalError> {
    if &header[..8] != protocol::BLOCK_MAGIC
        || u16_at(&header[8..10]) != JOURNAL_VERSION
        || u16_at(&header[10..12]) != 64
        || u32_at(&header[60..64]) != protocol::crc32c(&header[..60])
    {
        return Err(JournalError::Format("invalid block header".to_owned()));
    }
    let flags = u16_at(&header[14..16]);
    if flags & !block_flags::FINAL != 0 {
        return Err(JournalError::Format("unknown block flags".to_owned()));
    }
    Ok(ParsedBlock {
        codec: u16_at(&header[12..14]),
        block_sequence: u64_at(&header[16..24]),
        first_timestamp: u64_at(&header[24..32]),
        last_timestamp: u64_at(&header[32..40]),
        uncompressed_length: u32_at(&header[40..44]),
        stored_length: u32_at(&header[44..48]),
        record_count: u32_at(&header[48..52]),
        payload_crc32c: u32_at(&header[56..60]),
    })
}

fn parse_records(
    bytes: &[u8],
    cursor: u64,
    allow_tail: bool,
    initial_timestamp: u64,
) -> Result<(Vec<JournalEvent>, bool, u64), JournalError> {
    let mut position = 0;
    let mut previous = initial_timestamp;
    let mut events = Vec::new();
    while position < bytes.len() {
        let remaining = &bytes[position..];
        if remaining.len() < RECORD_HEADER_LENGTH {
            if allow_tail {
                return Ok((events, true, previous));
            }
            return Err(JournalError::Format("truncated record header".to_owned()));
        }
        let header = &remaining[..RECORD_HEADER_LENGTH];
        if &header[..4] != protocol::RECORD_MAGIC
            || u16_at(&header[4..6]) != JOURNAL_VERSION
            || u16_at(&header[6..8]) != 32
        {
            return Err(JournalError::Format("invalid record header".to_owned()));
        }
        let length = u32_at(&header[24..28]) as usize;
        if length > MAX_PAYLOAD_LENGTH {
            return Err(JournalError::Format("record payload is too large".to_owned()));
        }
        if remaining.len() < RECORD_HEADER_LENGTH + length {
            if allow_tail {
                return Ok((events, true, previous));
            }
            return Err(JournalError::Format("truncated record payload".to_owned()));
        }
        let timestamp = u64_at(&header[16..24]);
        if timestamp == 0 || timestamp <= previous {
            return Err(JournalError::Format("record timestamps are not increasing".to_owned()));
        }
        previous = timestamp;
        let payload = &remaining[RECORD_HEADER_LENGTH..RECORD_HEADER_LENGTH + length];
        if protocol::crc32c(payload) != u32_at(&header[28..32]) {
            return Err(JournalError::Format("bad record payload checksum".to_owned()));
        }
        if timestamp > cursor {
            let event_type = u16_at(&header[8..10]);
            let schema = u16_at(&header[10..12]);
            events.push(JournalEvent {
                event_type,
                payload_schema_version: schema,
                flags: u32_at(&header[12..16]),
                timestamp,
                payload: payload.to_vec(),
                opaque: !known_schema(event_type, schema),
            });
        }
        position += RECORD_HEADER_LENGTH + length;
    }
    Ok((events, false, previous))
}

fn count_records(bytes: &[u8], initial_timestamp: u64) -> Result<Vec<JournalEvent>, JournalError> {
    parse_records(bytes, 0, false, initial_timestamp).map(|(events, _, _)| events)
}

fn known_schema(event_type: u16, schema: u16) -> bool {
    schema == 1
        && matches!(
            event_type,
            0x0100 | 0x0101 | 0x0102 | 0x0200 | 0x0201 | 0x0202
        )
}

fn segment_path(directory: &Path, sequence: u64) -> PathBuf {
    directory.join(format!("journal-{sequence:06}.seg"))
}

fn segment_sequence(name: &std::ffi::OsStr) -> Option<u64> {
    let name = name.to_str()?;
    let number = name.strip_prefix("journal-")?.strip_suffix(".seg")?;
    if number.len() != 6 || !number.bytes().all(|byte| byte.is_ascii_digit()) {
        return None;
    }
    number.parse().ok()
}

fn epoch_millis() -> Result<u64, JournalError> {
    let elapsed = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|error| JournalError::Format(format!("system clock before epoch: {error}")))?;
    Ok(u64::try_from(elapsed.as_millis()).unwrap_or(u64::MAX))
}

fn u16_at(bytes: &[u8]) -> u16 {
    u16::from_le_bytes(bytes.try_into().unwrap())
}

fn u32_at(bytes: &[u8]) -> u32 {
    u32::from_le_bytes(bytes.try_into().unwrap())
}

fn u64_at(bytes: &[u8]) -> u64 {
    u64::from_le_bytes(bytes.try_into().unwrap())
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
    pub oldest_available_timestamp: Option<u64>,
    pub latest_timestamp: Option<u64>,
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
        || metadata.journal_format_version != JOURNAL_VERSION
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
    match (metadata.oldest_available_timestamp, metadata.latest_timestamp) {
        (None, None) => Ok(()),
        (Some(oldest), Some(latest)) if oldest != 0 && oldest <= latest => Ok(()),
        _ => Err(JournalError::Format("metadata timestamps are invalid".to_owned())),
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

    fn complete_segment(
        journal_id: [u8; 16],
        sequence: u64,
        timestamp: u64,
        record: Vec<u8>,
    ) -> Vec<u8> {
        let block = protocol::encode_block_header(BlockHeader {
            codec: codec::NONE,
            flags: block_flags::FINAL,
            block_sequence: 0,
            first_timestamp: timestamp,
            last_timestamp: timestamp,
            uncompressed_length: record.len() as u32,
            stored_length: record.len() as u32,
            record_count: 1,
            payload_crc32c: protocol::crc32c(&record),
        });
        let mut bytes = protocol::encode_segment_header(journal_id, sequence, 1).to_vec();
        bytes.extend_from_slice(&block);
        bytes.extend_from_slice(&record);
        bytes
    }

    #[test]
    fn appends_binary_events_with_strictly_increasing_timestamps() {
        let directory = temporary_directory("ordered-events");
        let mut writer = JournalWriter::create(&directory, [7; 16], JournalConfig::default()).unwrap();
        let first = writer
            .append_at(0, protocol::event_type::PTY_OUTPUT, 1, 0, &[0x1b, 0xff, b'\n'])
            .unwrap();
        let second = writer.append_at(0, 0x7ffe, 9, 0, &[]).unwrap();
        writer.flush().unwrap();

        assert_eq!((first, second), (1, 2));
        let result = read(&directory, 0).unwrap();
        assert_eq!(result.events.len(), 2);
        assert_eq!(result.events[0].payload, [0x1b, 0xff, b'\n']);
        assert!(!result.events[0].opaque);
        assert!(result.events[1].opaque);
        assert!(!result.ignored_crash_tail);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn ignores_a_truncated_uncompressed_record_tail() {
        let directory = temporary_directory("truncated-tail");
        let mut writer = JournalWriter::create(&directory, [8; 16], JournalConfig::default()).unwrap();
        writer
            .append_at(1, protocol::event_type::PTY_OUTPUT, 1, 0, b"complete")
            .unwrap();
        writer
            .append_at(2, protocol::event_type::PTY_OUTPUT, 1, 0, b"partial")
            .unwrap();
        writer.flush().unwrap();
        drop(writer);
        let path = segment_path(&directory, FIRST_SEGMENT);
        let mut bytes = fs::read(&path).unwrap();
        bytes.truncate(bytes.len() - 8);
        fs::write(path, bytes).unwrap();

        let result = read(&directory, 0).unwrap();
        assert_eq!(result.events.len(), 1);
        assert_eq!(result.events[0].payload, b"complete");
        assert!(result.ignored_crash_tail);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn rejects_timestamps_that_reset_in_a_later_segment() {
        let directory = temporary_directory("segment-order");
        let first = protocol::encode_record(Record {
            event_type: protocol::event_type::PTY_OUTPUT,
            payload_schema_version: 1,
            flags: 0,
            timestamp: 2,
            payload: b"first",
        })
        .unwrap();
        let second = protocol::encode_record(Record {
            event_type: protocol::event_type::PTY_OUTPUT,
            payload_schema_version: 1,
            flags: 0,
            timestamp: 1,
            payload: b"second",
        })
        .unwrap();
        fs::write(segment_path(&directory, 1), complete_segment([9; 16], 1, 2, first)).unwrap();
        fs::write(segment_path(&directory, 2), complete_segment([9; 16], 2, 1, second)).unwrap();

        assert!(read(&directory, 0).is_err());
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn reads_a_record_with_the_maximum_valid_payload_length() {
        let directory = temporary_directory("maximum-record");
        let mut writer = JournalWriter::create(&directory, [10; 16], JournalConfig::default()).unwrap();
        let payload = vec![0xa5; MAX_PAYLOAD_LENGTH];
        writer
            .append_at(1, protocol::event_type::PTY_OUTPUT, 1, 0, &payload)
            .unwrap();
        writer.flush().unwrap();

        assert_eq!(read(&directory, 0).unwrap().events[0].payload, payload);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn rejects_a_crash_tail_before_a_later_segment() {
        let directory = temporary_directory("non-final-tail");
        let record = protocol::encode_record(Record {
            event_type: protocol::event_type::PTY_OUTPUT,
            payload_schema_version: 1,
            flags: 0,
            timestamp: 1,
            payload: b"partial",
        })
        .unwrap();
        let record_length = record.len();
        let mut first = complete_segment([11; 16], 1, 1, record);
        first.truncate(first.len() - record_length);
        let second_record = protocol::encode_record(Record {
            event_type: protocol::event_type::PTY_OUTPUT,
            payload_schema_version: 1,
            flags: 0,
            timestamp: 2,
            payload: b"later",
        })
        .unwrap();
        fs::write(segment_path(&directory, 1), first).unwrap();
        fs::write(
            segment_path(&directory, 2),
            complete_segment([11; 16], 2, 2, second_record),
        )
        .unwrap();

        assert!(read(&directory, 0).is_err());
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn rejects_a_truncated_block_with_an_unknown_codec() {
        let directory = temporary_directory("unknown-codec");
        let block = protocol::encode_block_header(BlockHeader {
            codec: 99,
            flags: block_flags::FINAL,
            block_sequence: 0,
            first_timestamp: 1,
            last_timestamp: 1,
            uncompressed_length: 1,
            stored_length: 1,
            record_count: 1,
            payload_crc32c: 0,
        });
        let mut bytes = protocol::encode_segment_header([12; 16], 1, 1).to_vec();
        bytes.extend_from_slice(&block);
        fs::write(segment_path(&directory, 1), bytes).unwrap();

        assert!(read(&directory, 0).is_err());
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn ignores_reserved_bytes_in_v1_headers() {
        let directory = temporary_directory("reserved-header-bytes");
        let mut bytes = include_bytes!("../protocol/fixtures/journal-v1.bin").to_vec();
        bytes[48] = 1;
        let segment_checksum = protocol::crc32c(&bytes[..60]);
        bytes[60..64].copy_from_slice(&segment_checksum.to_le_bytes());
        let block = SEGMENT_HEADER_LENGTH;
        bytes[block + 52] = 1;
        let checksum = protocol::crc32c(&bytes[block..block + 60]);
        bytes[block + 60..block + 64].copy_from_slice(&checksum.to_le_bytes());
        fs::write(segment_path(&directory, 1), bytes).unwrap();

        assert_eq!(read(&directory, 0).unwrap().events.len(), 4);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn accepts_a_compatible_extended_segment_header() {
        let directory = temporary_directory("extended-segment-header");
        let fixture = include_bytes!("../protocol/fixtures/journal-v1.bin");
        let mut bytes = fixture[..SEGMENT_HEADER_LENGTH].to_vec();
        bytes[10..12].copy_from_slice(&72_u16.to_le_bytes());
        let checksum = protocol::crc32c(&bytes[..60]);
        bytes[60..64].copy_from_slice(&checksum.to_le_bytes());
        bytes.extend_from_slice(&[0; 8]);
        bytes.extend_from_slice(&fixture[SEGMENT_HEADER_LENGTH..]);
        fs::write(segment_path(&directory, 1), bytes).unwrap();

        assert_eq!(read(&directory, 0).unwrap().events.len(), 4);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn reads_the_v1_fixture_and_skips_an_unknown_event_as_opaque() {
        let directory = temporary_directory("fixture");
        fs::write(
            segment_path(&directory, FIRST_SEGMENT),
            include_bytes!("../protocol/fixtures/journal-v1.bin"),
        )
        .unwrap();

        let result = read(&directory, 2).unwrap();
        assert_eq!(result.events.len(), 2);
        assert_eq!(result.events[0].timestamp, 3);
        assert!(!result.events[0].opaque);
        assert_eq!(result.events[1].timestamp, 4);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn atomically_round_trips_valid_metadata() {
        let directory = temporary_directory("metadata");
        let metadata = Metadata {
            metadata_version: 1,
            journal_format_version: 1,
            control_protocol_version: 1,
            session_id: "session-1".to_owned(),
            journal_id: "00010203-0405-0607-0809-0a0b0c0d0e0f".to_owned(),
            created_at_epoch_millis: 1,
            session_start_epoch_millis: 2,
            command: vec!["bash".to_owned()],
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
            oldest_available_timestamp: None,
            latest_timestamp: None,
        };
        write_metadata(&directory, &metadata, Durability::EveryRecord).unwrap();
        assert_eq!(read_metadata(&directory).unwrap(), metadata);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn metadata_allows_an_empty_command_argument() {
        let directory = temporary_directory("empty-command-argument");
        let mut metadata = read_fixture_metadata();
        metadata.command = vec!["command".to_owned(), String::new()];

        write_metadata(&directory, &metadata, Durability::Buffered).unwrap();
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
        fs::write(
            directory.join(METADATA_NAME),
            serde_json::to_vec(&value).unwrap(),
        )
        .unwrap();

        assert_eq!(read_metadata(&directory).unwrap().state, SessionState::Running);
        fs::remove_dir_all(directory).unwrap();
    }

    fn read_fixture_metadata() -> Metadata {
        serde_json::from_str(include_str!("../protocol/fixtures/metadata-v1.json")).unwrap()
    }
}
