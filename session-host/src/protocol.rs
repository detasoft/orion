use std::fmt::{Display, Formatter};

pub const JOURNAL_VERSION: u16 = 1;
pub const CONTROL_VERSION: u16 = 1;
pub const CONTROL_HEADER_LENGTH: usize = 32;
pub const MAX_PAYLOAD_LENGTH: usize = 16 * 1024 * 1024;

pub const CONTROL_MAGIC: &[u8; 4] = b"ORCT";

pub mod event_type {
    pub const PTY_OUTPUT: u16 = 0x0100;
    pub const PTY_INPUT: u16 = 0x0101;
    pub const PTY_RESIZE: u16 = 0x0102;

    pub const PROCESS_STARTED: u16 = 0x0200;
    pub const PROCESS_EXITED: u16 = 0x0201;
    pub const SIGNAL: u16 = 0x0202;

    pub const HARNESS_MESSAGE: u16 = 0x1000;
    pub const HARNESS_STATUS: u16 = 0x1001;
    pub const TOOL_CALL: u16 = 0x1010;
    pub const TOOL_RESULT: u16 = 0x1011;
    pub const PROMPT: u16 = 0x1020;
    pub const ARTIFACT: u16 = 0x1030;
    pub const CHECKPOINT: u16 = 0x1040;
}

pub mod control_message {
    pub const INPUT: u16 = 0x0001;
    pub const RESIZE: u16 = 0x0002;
    pub const SIGNAL: u16 = 0x0003;
    pub const TERMINATE: u16 = 0x0004;
    pub const STATUS: u16 = 0x0005;
    pub const APPEND_EVENT: u16 = 0x0006;

    pub const ACCEPTED: u16 = 0x8000;
    pub const DUPLICATE: u16 = 0x8001;
    pub const ERROR: u16 = 0x8002;
    pub const STATUS_RESPONSE: u16 = 0x8003;
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ControlFrame<'a> {
    pub message_type: u16,
    pub payload_schema_version: u16,
    pub flags: u32,
    pub request_id: u64,
    pub payload: &'a [u8],
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum EncodeError {
    PayloadTooLarge { actual: usize, maximum: usize },
    InvalidPayload(&'static str),
}

impl Display for EncodeError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::PayloadTooLarge { actual, maximum } => {
                write!(formatter, "payload length {actual} exceeds maximum {maximum}")
            }
            Self::InvalidPayload(message) => formatter.write_str(message),
        }
    }
}

impl std::error::Error for EncodeError {}

pub fn encode_control_frame(frame: ControlFrame<'_>) -> Result<Vec<u8>, EncodeError> {
    validate_payload_length(frame.payload.len())?;
    let mut encoded = vec![0_u8; CONTROL_HEADER_LENGTH + frame.payload.len()];
    encoded[0..4].copy_from_slice(CONTROL_MAGIC);
    put_u16(&mut encoded[4..6], CONTROL_VERSION);
    put_u16(&mut encoded[6..8], CONTROL_HEADER_LENGTH as u16);
    put_u16(&mut encoded[8..10], frame.message_type);
    put_u16(&mut encoded[10..12], frame.payload_schema_version);
    put_u32(&mut encoded[12..16], frame.flags);
    put_u64(&mut encoded[16..24], frame.request_id);
    put_u32(&mut encoded[24..28], frame.payload.len() as u32);
    put_u32(&mut encoded[28..32], crc32c(frame.payload));
    encoded[CONTROL_HEADER_LENGTH..].copy_from_slice(frame.payload);
    Ok(encoded)
}

pub fn pty_input_payload(input_id: [u8; 16], bytes: &[u8]) -> Result<Vec<u8>, EncodeError> {
    let payload_length = 16_usize.saturating_add(bytes.len());
    validate_payload_length(payload_length)?;
    let mut payload = Vec::with_capacity(payload_length);
    payload.extend_from_slice(&input_id);
    payload.extend_from_slice(bytes);
    Ok(payload)
}

pub fn pty_resize_payload(cols: u32, rows: u32) -> [u8; 8] {
    let mut payload = [0_u8; 8];
    put_u32(&mut payload[0..4], cols);
    put_u32(&mut payload[4..8], rows);
    payload
}

pub fn process_exited_payload(exit_code: i32, signal: i32) -> [u8; 8] {
    let mut payload = [0_u8; 8];
    payload[0..4].copy_from_slice(&exit_code.to_le_bytes());
    payload[4..8].copy_from_slice(&signal.to_le_bytes());
    payload
}

pub fn encode_pty_output(event_id: u64, bytes: &[u8]) -> Result<Vec<u8>, EncodeError> {
    validate_payload_length(bytes.len())?;
    let mut encoded = event_prefix(event_id, event_type::PTY_OUTPUT);
    cbor_bytes(&mut encoded, bytes);
    Ok(encoded)
}

pub fn encode_pty_input(
    event_id: u64,
    command_id: &str,
    bytes: &[u8],
) -> Result<Vec<u8>, EncodeError> {
    validate_payload_length(bytes.len())?;
    let mut encoded = event_prefix(event_id, event_type::PTY_INPUT);
    cbor_array(&mut encoded, 2);
    cbor_text(&mut encoded, command_id);
    cbor_bytes(&mut encoded, bytes);
    Ok(encoded)
}

pub fn encode_pty_resize(event_id: u64, cols: u32, rows: u32) -> Result<Vec<u8>, EncodeError> {
    if !valid_terminal_dimensions(cols, rows) {
        return Err(EncodeError::InvalidPayload(
            "terminal dimensions must be between 1 and 65535",
        ));
    }
    let mut encoded = event_prefix(event_id, event_type::PTY_RESIZE);
    cbor_array(&mut encoded, 2);
    cbor_unsigned(&mut encoded, u64::from(cols));
    cbor_unsigned(&mut encoded, u64::from(rows));
    Ok(encoded)
}

pub fn encode_process_exited(event_id: u64, exit_code: i32) -> Vec<u8> {
    let mut encoded = event_prefix(event_id, event_type::PROCESS_EXITED);
    cbor_array(&mut encoded, 1);
    cbor_signed(&mut encoded, i64::from(exit_code));
    encoded
}

pub fn encode_process_started(event_id: u64, process_id: u64) -> Result<Vec<u8>, EncodeError> {
    if process_id == 0 {
        return Err(EncodeError::InvalidPayload("process ID must be nonzero"));
    }
    let mut encoded = event_prefix(event_id, event_type::PROCESS_STARTED);
    cbor_array(&mut encoded, 1);
    cbor_unsigned(&mut encoded, process_id);
    Ok(encoded)
}

pub fn encode_signal(event_id: u64, kind: u16, platform_code: i32) -> Result<Vec<u8>, EncodeError> {
    if !valid_signal(kind, platform_code) {
        return Err(EncodeError::InvalidPayload(
            "signal kind or platform code is invalid",
        ));
    }
    let mut encoded = event_prefix(event_id, event_type::SIGNAL);
    cbor_array(&mut encoded, 2);
    cbor_unsigned(&mut encoded, u64::from(kind));
    cbor_signed(&mut encoded, i64::from(platform_code));
    Ok(encoded)
}

pub fn valid_terminal_dimensions(cols: u32, rows: u32) -> bool {
    (1..=65535).contains(&cols) && (1..=65535).contains(&rows)
}

pub fn valid_signal(kind: u16, platform_code: i32) -> bool {
    match kind {
        1..=5 => platform_code >= -1,
        0xffff => platform_code >= 0,
        _ => false,
    }
}

pub fn encode_binary_event(
    event_id: u64,
    event_type: u16,
    payload: &[u8],
) -> Result<Vec<u8>, EncodeError> {
    validate_payload_length(payload.len())?;
    let mut encoded = event_prefix(event_id, event_type);
    cbor_bytes(&mut encoded, payload);
    Ok(encoded)
}

pub fn encode_opaque_event(
    event_id: u64,
    event_type: u16,
    encoded_payload: &[u8],
    trailing_fields: &[&[u8]],
) -> Result<Vec<u8>, EncodeError> {
    validate_payload_length(encoded_payload.len())?;
    let mut encoded = Vec::new();
    cbor_array(&mut encoded, 3 + trailing_fields.len());
    cbor_unsigned(&mut encoded, event_id);
    cbor_unsigned(&mut encoded, u64::from(event_type));
    encoded.extend_from_slice(encoded_payload);
    for field in trailing_fields {
        encoded.extend_from_slice(field);
    }
    Ok(encoded)
}

pub fn crc32c(bytes: &[u8]) -> u32 {
    let mut crc = !0_u32;
    for byte in bytes {
        crc ^= u32::from(*byte);
        for _ in 0..8 {
            crc = (crc >> 1) ^ (0x82f6_3b78_u32 & (0_u32.wrapping_sub(crc & 1)));
        }
    }
    !crc
}

fn event_prefix(event_id: u64, event_type: u16) -> Vec<u8> {
    let mut encoded = Vec::new();
    cbor_array(&mut encoded, 3);
    cbor_unsigned(&mut encoded, event_id);
    cbor_unsigned(&mut encoded, u64::from(event_type));
    encoded
}

fn cbor_array(encoded: &mut Vec<u8>, length: usize) {
    cbor_argument(encoded, 4, length as u64);
}

fn cbor_bytes(encoded: &mut Vec<u8>, bytes: &[u8]) {
    cbor_argument(encoded, 2, bytes.len() as u64);
    encoded.extend_from_slice(bytes);
}

fn cbor_text(encoded: &mut Vec<u8>, value: &str) {
    cbor_argument(encoded, 3, value.len() as u64);
    encoded.extend_from_slice(value.as_bytes());
}

fn cbor_signed(encoded: &mut Vec<u8>, value: i64) {
    if value >= 0 {
        cbor_unsigned(encoded, value as u64);
    } else {
        cbor_argument(encoded, 1, (-1_i128 - i128::from(value)) as u64);
    }
}

fn cbor_unsigned(encoded: &mut Vec<u8>, value: u64) {
    cbor_argument(encoded, 0, value);
}

fn cbor_argument(encoded: &mut Vec<u8>, major: u8, value: u64) {
    let prefix = major << 5;
    match value {
        0..=23 => encoded.push(prefix | value as u8),
        24..=0xff => encoded.extend_from_slice(&[prefix | 24, value as u8]),
        0x100..=0xffff => {
            encoded.push(prefix | 25);
            encoded.extend_from_slice(&(value as u16).to_be_bytes());
        }
        0x1_0000..=0xffff_ffff => {
            encoded.push(prefix | 26);
            encoded.extend_from_slice(&(value as u32).to_be_bytes());
        }
        _ => {
            encoded.push(prefix | 27);
            encoded.extend_from_slice(&value.to_be_bytes());
        }
    }
}

fn validate_payload_length(length: usize) -> Result<(), EncodeError> {
    if length > MAX_PAYLOAD_LENGTH {
        return Err(EncodeError::PayloadTooLarge {
            actual: length,
            maximum: MAX_PAYLOAD_LENGTH,
        });
    }
    Ok(())
}

fn put_u16(target: &mut [u8], value: u16) {
    target.copy_from_slice(&value.to_le_bytes());
}

fn put_u32(target: &mut [u8], value: u32) {
    target.copy_from_slice(&value.to_le_bytes());
}

fn put_u64(target: &mut [u8], value: u64) {
    target.copy_from_slice(&value.to_le_bytes());
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn required_events_match_the_shared_cbor_sequence_fixture() {
        assert_eq!(protocol_fixture::journal(), fixture("session-events-v1.hex"));
        assert_eq!(
            protocol_fixture::journal_hex(),
            include_str!("../protocol/fixtures/session-events-v1.hex"),
        );
        assert_eq!(
            include_str!("../../agent-protocol/protocol/fixtures/session-events-v1.hex"),
            include_str!("../protocol/fixtures/session-events-v1.hex"),
        );
        assert!(!protocol_fixture::journal()
            .windows(8)
            .any(|bytes| bytes == b"ORJSEG01" || bytes == b"ORJBLK01"));
    }

    #[test]
    fn unknown_event_fixture_preserves_payload_and_trailing_field() {
        assert_eq!(
            protocol_fixture::unknown_event(),
            fixture("session-event-unknown-tail-v1.hex"),
        );
        assert_eq!(
            include_str!("../../agent-protocol/protocol/fixtures/session-event-unknown-tail-v1.hex"),
            include_str!("../protocol/fixtures/session-event-unknown-tail-v1.hex"),
        );
        assert_eq!(
            protocol_fixture::unknown_event_hex(),
            include_str!("../protocol/fixtures/session-event-unknown-tail-v1.hex"),
        );
    }

    #[test]
    fn checked_in_crash_tail_fixture_is_stable() {
        assert_eq!(
            protocol_fixture::truncated_item(),
            fixture("truncated-item-v1.hex"),
        );
        assert_eq!(
            protocol_fixture::truncated_item_hex(),
            include_str!("../protocol/fixtures/truncated-item-v1.hex"),
        );
    }

    #[test]
    fn checked_in_control_fixture_is_stable() {
        assert_eq!(
            include_bytes!("../protocol/fixtures/control-v1.bin").as_slice(),
            protocol_fixture::control().as_slice(),
        );
    }

    #[test]
    fn metadata_fixture_declares_event_id_bounds() {
        let metadata = include_str!("../protocol/fixtures/metadata-v1.json");
        assert!(metadata.contains("\"oldestAvailableEventId\": 1"));
        assert!(metadata.contains("\"latestEventId\": 4"));
        assert!(!metadata.contains("Timestamp"));
    }

    #[test]
    fn crc32c_matches_standard_check_value() {
        assert_eq!(crc32c(b"123456789"), 0xe306_9283);
    }

    fn fixture(name: &str) -> Vec<u8> {
        let value = match name {
            "session-events-v1.hex" => include_str!("../protocol/fixtures/session-events-v1.hex"),
            "session-event-unknown-tail-v1.hex" => {
                include_str!("../protocol/fixtures/session-event-unknown-tail-v1.hex")
            }
            "truncated-item-v1.hex" => include_str!("../protocol/fixtures/truncated-item-v1.hex"),
            _ => unreachable!(),
        };
        parse_hex(value)
    }

    fn parse_hex(value: &str) -> Vec<u8> {
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
}

pub mod protocol_fixture {
    use super::*;

    pub fn journal() -> Vec<u8> {
        journal_records().concat()
    }

    pub fn journal_hex() -> String {
        format_hex_records(&journal_records())
    }

    pub fn unknown_event() -> Vec<u8> {
        encode_opaque_event(5, 0x7ffe, &[0x44, 0xde, 0xad, 0xbe, 0xef], &[b"ffuture"])
            .unwrap()
    }

    pub fn unknown_event_hex() -> String {
        format_hex_records(&[unknown_event()])
    }

    pub fn truncated_item() -> Vec<u8> {
        truncated_item_records().concat()
    }

    pub fn truncated_item_hex() -> String {
        format_hex_records(&truncated_item_records())
    }

    pub fn control() -> Vec<u8> {
        let input_id = [
            0xf0, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9, 0xfa, 0xfb, 0xfc, 0xfd,
            0xfe, 0xff,
        ];
        let input = pty_input_payload(input_id, b"echo hello\n").unwrap();
        let request = encode_control_frame(ControlFrame {
            message_type: control_message::INPUT,
            payload_schema_version: 1,
            flags: 0,
            request_id: 42,
            payload: &input,
        })
        .unwrap();
        let response_payload = 1_u64.to_le_bytes();
        let response = encode_control_frame(ControlFrame {
            message_type: control_message::ACCEPTED,
            payload_schema_version: 1,
            flags: 0,
            request_id: 42,
            payload: &response_payload,
        })
        .unwrap();
        [request, response].concat()
    }

    fn journal_records() -> Vec<Vec<u8>> {
        vec![
            encode_pty_output(1, &[0, 0x1b, 0xff]).unwrap(),
            encode_pty_resize(2, 180, 50).unwrap(),
            encode_pty_input(3, "00010203-0405-0607-0809-0a0b0c0d0e0f", &[0, 0xff]).unwrap(),
            encode_process_exited(4, 0),
        ]
    }

    fn truncated_item_records() -> Vec<Vec<u8>> {
        let complete = encode_pty_output(1, b"complete").unwrap();
        let mut partial = encode_pty_output(2, b"partial").unwrap();
        partial.truncate(partial.len() - 3);
        vec![complete, partial]
    }

    fn format_hex_records(records: &[Vec<u8>]) -> String {
        const HEX: &[u8; 16] = b"0123456789abcdef";
        let mut formatted = String::new();
        for record in records {
            for byte in record {
                formatted.push(char::from(HEX[usize::from(byte >> 4)]));
                formatted.push(char::from(HEX[usize::from(byte & 0x0f)]));
            }
            formatted.push('\n');
        }
        formatted
    }
}
