use std::collections::BTreeMap;
use std::fs::{self, File};
use std::io::{self, Read};
use std::path::{Path, PathBuf};

use orion_session_host::protocol::{self, MAX_PAYLOAD_LENGTH};

const MAX_CBOR_DEPTH: usize = 64;
const MAX_RECORD_FIELDS: usize = 1024;
const MAX_ENCODED_RECORD_LENGTH: usize = MAX_PAYLOAD_LENGTH + 4096;
const MAX_DECOMPRESSED_SEGMENT_LENGTH: u64 = 512 * 1024 * 1024;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct JournalEvent {
    pub event_id: u64,
    pub event_type: u16,
    pub payload: Vec<u8>,
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
pub enum ReadError {
    Io(io::Error),
    Format(String),
}

impl From<io::Error> for ReadError {
    fn from(error: io::Error) -> Self {
        Self::Io(error)
    }
}

impl std::fmt::Display for ReadError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Io(error) => write!(formatter, "journal read I/O error: {error}"),
            Self::Format(message) => write!(formatter, "invalid journal: {message}"),
        }
    }
}

pub fn read(directory: impl AsRef<Path>, event_id: u64) -> Result<ReadResult, ReadError> {
    read_after(directory, event_id)
}

pub fn read_after(
    directory: impl AsRef<Path>,
    requested_event_id: u64,
) -> Result<ReadResult, ReadError> {
    let directory = directory.as_ref();
    let first = read_snapshot(directory, requested_event_id);
    if matches!(&first, Err(ReadError::Io(error)) if error.kind() == io::ErrorKind::NotFound) {
        return read_snapshot(directory, requested_event_id);
    }
    first
}

fn read_snapshot(directory: &Path, requested_event_id: u64) -> Result<ReadResult, ReadError> {
    let segments = discover_segments(directory)?;
    let mut events = Vec::new();
    let mut ignored_crash_tail = false;
    let mut previous_event_id = 0;
    for (index, segment) in segments.iter().enumerate() {
        let allow_tail = index + 1 == segments.len() && !segment.compressed;
        let bytes = read_segment(segment)?;
        let scan = scan_sequence(&bytes, requested_event_id, allow_tail, previous_event_id)?;
        previous_event_id = scan.last_event_id;
        events.extend(scan.events);
        ignored_crash_tail |= scan.ignored_crash_tail;
    }
    let first_available_event_id = first_event_id(&segments)?;
    let gap = first_available_event_id
        .filter(|first| requested_event_id < *first)
        .map(|first_available_event_id| RetentionGap {
            requested_event_id,
            first_available_event_id,
        });
    Ok(ReadResult {
        events,
        gap,
        ignored_crash_tail,
    })
}

#[derive(Clone, Debug)]
struct Segment {
    number: u64,
    path: PathBuf,
    compressed: bool,
}

fn discover_segments(directory: &Path) -> Result<Vec<Segment>, ReadError> {
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
            segments.push(Segment {
                number,
                path,
                compressed: false,
            });
        } else if let Some(path) = compressed {
            segments.push(Segment {
                number,
                path,
                compressed: true,
            });
        }
    }
    for pair in segments.windows(2) {
        if pair[0].number.checked_add(1) != Some(pair[1].number) {
            return Err(ReadError::Format("segment sequence has a gap".to_owned()));
        }
    }
    Ok(segments)
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

fn read_segment(segment: &Segment) -> Result<Vec<u8>, ReadError> {
    if !segment.compressed {
        return Ok(fs::read(&segment.path)?);
    }
    let file = File::open(&segment.path)?;
    let decoder = zstd::stream::read::Decoder::new(file)?;
    let mut bytes = Vec::new();
    decoder
        .take(MAX_DECOMPRESSED_SEGMENT_LENGTH + 1)
        .read_to_end(&mut bytes)?;
    if bytes.len() as u64 > MAX_DECOMPRESSED_SEGMENT_LENGTH {
        return Err(ReadError::Format(
            "decompressed journal segment exceeds the size limit".to_owned(),
        ));
    }
    Ok(bytes)
}

fn first_event_id(segments: &[Segment]) -> Result<Option<u64>, ReadError> {
    for segment in segments {
        let bytes = read_segment(segment)?;
        if bytes.is_empty() {
            continue;
        }
        let end = match item_end(&bytes, 0, 0) {
            Ok(end) => end,
            Err(ParseFailure::Incomplete) if !segment.compressed => continue,
            Err(error) => return Err(parse_error(error)),
        };
        return Ok(Some(parse_record(&bytes[..end])?.event_id));
    }
    Ok(None)
}

struct SegmentScan {
    events: Vec<JournalEvent>,
    ignored_crash_tail: bool,
    last_event_id: u64,
}

fn scan_sequence(
    bytes: &[u8],
    requested_event_id: u64,
    allow_tail: bool,
    initial_event_id: u64,
) -> Result<SegmentScan, ReadError> {
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
            return Err(ReadError::Format("CBOR journal record is too large".to_owned()));
        }
        let event = parse_record(&bytes[position..end])?;
        if event.event_id == 0 || event.event_id <= previous_event_id {
            return Err(ReadError::Format(
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
    })
}

fn parse_record(encoded: &[u8]) -> Result<JournalEvent, ReadError> {
    let fields = array_fields(encoded)?;
    if fields.len() < 3 {
        return Err(ReadError::Format(
            "session event must contain at least three fields".to_owned(),
        ));
    }
    let event_id = decode_unsigned(field(encoded, fields[0]), "eventId")?;
    let event_type = u16::try_from(decode_unsigned(field(encoded, fields[1]), "eventType")?)
        .map_err(|_| ReadError::Format("eventType exceeds u16".to_owned()))?;
    let payload = decode_payload(event_type, field(encoded, fields[2]))?;
    Ok(JournalEvent {
        event_id,
        event_type,
        payload,
    })
}

fn decode_payload(event_type: u16, encoded: &[u8]) -> Result<Vec<u8>, ReadError> {
    match event_type {
        protocol::event_type::COMMAND_ACCEPTED => {
            let fields = array_fields(encoded)?;
            require_fields(&fields, 2, "COMMAND_ACCEPTED")?;
            let sequence = decode_unsigned(field(encoded, fields[0]), "operationSequence")?;
            let envelope = decode_bytes(field(encoded, fields[1]))?;
            Ok([sequence.to_le_bytes().as_slice(), envelope.as_slice()].concat())
        }
        protocol::event_type::COMMAND_RESULT => {
            let fields = array_fields(encoded)?;
            require_fields(&fields, 4, "COMMAND_RESULT")?;
            let sequence = decode_unsigned(field(encoded, fields[0]), "operationSequence")?;
            let command_id = decode_bytes(field(encoded, fields[1]))?;
            let outcome = u8::try_from(decode_unsigned(field(encoded, fields[2]), "outcome")?)
                .map_err(|_| ReadError::Format("COMMAND_RESULT outcome exceeds u8".to_owned()))?;
            let detail = decode_text(field(encoded, fields[3]))?;
            let command_id_length = u16::try_from(command_id.len())
                .map_err(|_| ReadError::Format("COMMAND_RESULT command ID is too long".to_owned()))?;
            let mut payload = Vec::new();
            payload.extend_from_slice(&sequence.to_le_bytes());
            payload.extend_from_slice(&command_id_length.to_le_bytes());
            payload.extend_from_slice(&command_id);
            payload.push(outcome);
            payload.extend_from_slice(detail.as_bytes());
            Ok(payload)
        }
        protocol::event_type::PTY_OUTPUT => decode_bytes(encoded),
        protocol::event_type::PTY_INPUT => {
            let fields = array_fields(encoded)?;
            require_fields(&fields, 2, "PTY_INPUT")?;
            let command_id = decode_text(field(encoded, fields[0]))?;
            let command_id = parse_uuid(&command_id)
                .ok_or_else(|| ReadError::Format("PTY_INPUT command ID is invalid".to_owned()))?;
            let bytes = decode_bytes(field(encoded, fields[1]))?;
            Ok([command_id.as_slice(), bytes.as_slice()].concat())
        }
        protocol::event_type::PTY_RESIZE => {
            let fields = array_fields(encoded)?;
            require_fields(&fields, 2, "PTY_RESIZE")?;
            let cols = u32::try_from(decode_unsigned(field(encoded, fields[0]), "cols")?)
                .map_err(|_| ReadError::Format("PTY_RESIZE cols exceeds u32".to_owned()))?;
            let rows = u32::try_from(decode_unsigned(field(encoded, fields[1]), "rows")?)
                .map_err(|_| ReadError::Format("PTY_RESIZE rows exceeds u32".to_owned()))?;
            Ok(protocol::pty_resize_payload(cols, rows).to_vec())
        }
        protocol::event_type::PROCESS_STARTED => {
            let fields = array_fields(encoded)?;
            require_fields(&fields, 1, "PROCESS_STARTED")?;
            Ok(decode_unsigned(field(encoded, fields[0]), "process ID")?
                .to_le_bytes()
                .to_vec())
        }
        protocol::event_type::PROCESS_EXITED => {
            let fields = array_fields(encoded)?;
            require_fields(&fields, 1, "PROCESS_EXITED")?;
            let exit_code = i32::try_from(decode_signed(field(encoded, fields[0]))?)
                .map_err(|_| ReadError::Format("PROCESS_EXITED code exceeds i32".to_owned()))?;
            Ok(protocol::process_exited_payload(exit_code, -1).to_vec())
        }
        protocol::event_type::SIGNAL => {
            let fields = array_fields(encoded)?;
            require_fields(&fields, 2, "SIGNAL")?;
            let kind = u16::try_from(decode_unsigned(field(encoded, fields[0]), "signal kind")?)
                .map_err(|_| ReadError::Format("signal kind exceeds u16".to_owned()))?;
            let code = i32::try_from(decode_signed(field(encoded, fields[1]))?)
                .map_err(|_| ReadError::Format("signal code exceeds i32".to_owned()))?;
            let mut payload = Vec::with_capacity(8);
            payload.extend_from_slice(&kind.to_le_bytes());
            payload.extend_from_slice(&0_u16.to_le_bytes());
            payload.extend_from_slice(&code.to_le_bytes());
            Ok(payload)
        }
        protocol::event_type::SESSION_START_FAILED => {
            let fields = array_fields(encoded)?;
            require_fields(&fields, 3, "SESSION_START_FAILED")?;
            let command_id = decode_text(field(encoded, fields[0]))?;
            let diagnostic = decode_text(field(encoded, fields[1]))?;
            let omitted = decode_unsigned(field(encoded, fields[2]), "omitted byte count")?;
            protocol::session_start_failed_payload(&command_id, &diagnostic, omitted)
                .map_err(|error| ReadError::Format(error.to_string()))
        }
        _ => Ok(encoded.to_vec()),
    }
}

fn require_fields(
    fields: &[(usize, usize)],
    minimum: usize,
    event: &str,
) -> Result<(), ReadError> {
    if fields.len() < minimum {
        return Err(ReadError::Format(format!("{event} payload has missing fields")));
    }
    Ok(())
}

fn field(encoded: &[u8], field: (usize, usize)) -> &[u8] {
    &encoded[field.0..field.1]
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum ParseFailure {
    Incomplete,
    Invalid(&'static str),
}

fn parse_error(error: ParseFailure) -> ReadError {
    match error {
        ParseFailure::Incomplete => ReadError::Format("incomplete CBOR item".to_owned()),
        ParseFailure::Invalid(message) => ReadError::Format(message.to_owned()),
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
                return Err(ParseFailure::Invalid(
                    "indefinite CBOR map has an unmatched key",
                ));
            }
            return Ok(position + 1);
        }
        if matches!(major, 2 | 3) && (next >> 5 != major || next & 0x1f == 31) {
            return Err(ParseFailure::Invalid("invalid CBOR indefinite string chunk"));
        }
        position = item_end(bytes, position, depth + 1)?;
        map_items += 1;
    }
}

fn argument(bytes: &[u8], start: usize, additional: u8) -> Result<(u64, usize), ParseFailure> {
    match additional {
        value @ 0..=23 => Ok((u64::from(value), start + 1)),
        24 => Ok((
            u64::from(*bytes.get(start + 1).ok_or(ParseFailure::Incomplete)?),
            start + 2,
        )),
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

fn array_fields(encoded: &[u8]) -> Result<Vec<(usize, usize)>, ReadError> {
    let initial = *encoded
        .first()
        .ok_or_else(|| ReadError::Format("empty CBOR item".to_owned()))?;
    if initial >> 5 != 4 {
        return Err(ReadError::Format(
            "session event field must be a CBOR array".to_owned(),
        ));
    }
    let additional = initial & 0x1f;
    let mut fields = Vec::new();
    let mut position;
    if additional == 31 {
        position = 1;
        while encoded.get(position) != Some(&0xff) {
            if fields.len() == MAX_RECORD_FIELDS {
                return Err(ReadError::Format("CBOR array has too many fields".to_owned()));
            }
            let end = item_end(encoded, position, 1).map_err(parse_error)?;
            fields.push((position, end));
            position = end;
        }
        position += 1;
    } else {
        let (length, content) = argument(encoded, 0, additional).map_err(parse_error)?;
        let length = usize::try_from(length)
            .map_err(|_| ReadError::Format("CBOR array length exceeds platform".to_owned()))?;
        if length > MAX_RECORD_FIELDS {
            return Err(ReadError::Format("CBOR array has too many fields".to_owned()));
        }
        position = content;
        for _ in 0..length {
            let end = item_end(encoded, position, 1).map_err(parse_error)?;
            fields.push((position, end));
            position = end;
        }
    }
    if position != encoded.len() {
        return Err(ReadError::Format("CBOR array has trailing bytes".to_owned()));
    }
    Ok(fields)
}

fn decode_unsigned(encoded: &[u8], name: &str) -> Result<u64, ReadError> {
    let initial = encoded
        .first()
        .ok_or_else(|| ReadError::Format(format!("{name} is empty")))?;
    if initial >> 5 != 0 || initial & 0x1f == 31 {
        return Err(ReadError::Format(format!(
            "{name} must be an unsigned integer"
        )));
    }
    let (value, end) = argument(encoded, 0, initial & 0x1f).map_err(parse_error)?;
    if end != encoded.len() {
        return Err(ReadError::Format(format!("{name} contains trailing bytes")));
    }
    Ok(value)
}

fn decode_signed(encoded: &[u8]) -> Result<i64, ReadError> {
    let initial = *encoded
        .first()
        .ok_or_else(|| ReadError::Format("signed integer is empty".to_owned()))?;
    let major = initial >> 5;
    if !matches!(major, 0 | 1) || initial & 0x1f == 31 {
        return Err(ReadError::Format(
            "value must be a signed integer".to_owned(),
        ));
    }
    let (value, end) = argument(encoded, 0, initial & 0x1f).map_err(parse_error)?;
    if end != encoded.len() {
        return Err(ReadError::Format(
            "signed integer contains trailing bytes".to_owned(),
        ));
    }
    if major == 0 {
        return i64::try_from(value)
            .map_err(|_| ReadError::Format("signed integer exceeds i64".to_owned()));
    }
    i64::try_from(-1_i128 - i128::from(value))
        .map_err(|_| ReadError::Format("signed integer is below i64".to_owned()))
}

fn decode_bytes(encoded: &[u8]) -> Result<Vec<u8>, ReadError> {
    decode_string(encoded, 2)
}

fn decode_text(encoded: &[u8]) -> Result<String, ReadError> {
    String::from_utf8(decode_string(encoded, 3)?)
        .map_err(|_| ReadError::Format("CBOR text is not valid UTF-8".to_owned()))
}

fn decode_string(encoded: &[u8], expected_major: u8) -> Result<Vec<u8>, ReadError> {
    let initial = *encoded
        .first()
        .ok_or_else(|| ReadError::Format("CBOR string is empty".to_owned()))?;
    if initial >> 5 != expected_major {
        return Err(ReadError::Format(
            "CBOR value has the wrong string type".to_owned(),
        ));
    }
    if initial & 0x1f == 31 {
        return decode_indefinite_string(encoded, expected_major);
    }
    let (length, content) = argument(encoded, 0, initial & 0x1f).map_err(parse_error)?;
    let length = usize::try_from(length)
        .map_err(|_| ReadError::Format("CBOR string length exceeds platform".to_owned()))?;
    let end = content
        .checked_add(length)
        .filter(|end| *end == encoded.len())
        .ok_or_else(|| ReadError::Format("CBOR string length does not match item".to_owned()))?;
    Ok(encoded[content..end].to_vec())
}

fn decode_indefinite_string(encoded: &[u8], expected_major: u8) -> Result<Vec<u8>, ReadError> {
    let mut decoded = Vec::new();
    let mut position = 1;
    loop {
        let initial = *encoded
            .get(position)
            .ok_or_else(|| ReadError::Format("CBOR indefinite string has no break".to_owned()))?;
        if initial == 0xff {
            if position + 1 != encoded.len() {
                return Err(ReadError::Format(
                    "CBOR indefinite string has trailing bytes".to_owned(),
                ));
            }
            return Ok(decoded);
        }
        if initial >> 5 != expected_major || initial & 0x1f == 31 {
            return Err(ReadError::Format(
                "invalid CBOR indefinite string chunk".to_owned(),
            ));
        }
        let (length, content) = argument(encoded, position, initial & 0x1f).map_err(parse_error)?;
        let length = usize::try_from(length)
            .map_err(|_| ReadError::Format("CBOR string length exceeds platform".to_owned()))?;
        let end = content
            .checked_add(length)
            .filter(|end| *end <= encoded.len())
            .ok_or_else(|| ReadError::Format("CBOR string chunk is incomplete".to_owned()))?;
        decoded.extend_from_slice(&encoded[content..end]);
        position = end;
    }
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

#[cfg(test)]
fn temporary_directory(name: &str) -> PathBuf {
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::time::{SystemTime, UNIX_EPOCH};

    static NEXT_DIRECTORY: AtomicU64 = AtomicU64::new(0);
    let unique = NEXT_DIRECTORY.fetch_add(1, Ordering::Relaxed);
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    let directory = std::env::temp_dir().join(format!(
        "orion-journal-support-{name}-{}-{nanos}-{unique}",
        std::process::id()
    ));
    fs::create_dir_all(&directory).unwrap();
    directory
}

#[cfg(test)]
fn hex_bytes(value: &str) -> Vec<u8> {
    value
        .as_bytes()
        .chunks_exact(2)
        .map(|digits| {
            let digits = std::str::from_utf8(digits).unwrap();
            u8::from_str_radix(digits, 16).unwrap()
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use std::fs;

    #[test]
    fn reads_multiple_records_after_a_cursor() {
        let directory = super::temporary_directory("multiple-records");
        fs::write(
            directory.join("00000001.cbor"),
            [
                super::hex_bytes("83011901004161"),
                super::hex_bytes("83021901004162"),
            ]
            .concat(),
        )
        .unwrap();

        let result = super::read_after(&directory, 1).unwrap();

        assert_eq!(result.events.len(), 1);
        assert_eq!(result.events[0].event_id, 2);
        assert_eq!(result.events[0].payload, b"b");
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn ignores_only_an_incomplete_active_tail() {
        let directory = super::temporary_directory("incomplete-tail");
        fs::write(
            directory.join("00000001.cbor"),
            [
                super::hex_bytes("83011901004161"),
                super::hex_bytes("83021901004362"),
            ]
            .concat(),
        )
        .unwrap();

        let result = super::read_after(&directory, 0).unwrap();

        assert_eq!(result.events.len(), 1);
        assert!(result.ignored_crash_tail);
        fs::remove_dir_all(directory).unwrap();
    }
}
