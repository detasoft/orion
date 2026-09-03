use std::fmt::{Display, Formatter};

pub const JOURNAL_VERSION: u16 = 1;
pub const CONTROL_VERSION: u16 = 1;
pub const CONTROL_HEADER_LENGTH: usize = 32;
pub const MAX_PAYLOAD_LENGTH: usize = 16 * 1024 * 1024;
pub const MAX_START_DIAGNOSTIC_BYTES: usize = 1024 * 1024;
pub const START_DIAGNOSTIC_PREFIX_BYTES: usize = 64 * 1024;
pub const START_DIAGNOSTIC_SUFFIX_BYTES: usize = 960 * 1024;

pub const CONTROL_MAGIC: &[u8; 4] = b"ORCT";

pub mod event_type {
    pub const COMMAND_ACCEPTED: u16 = 0x0001;
    pub const COMMAND_RESULT: u16 = 0x0002;

    pub const PTY_OUTPUT: u16 = 0x0100;
    pub const PTY_INPUT: u16 = 0x0101;
    pub const PTY_RESIZE: u16 = 0x0102;

    pub const PROCESS_STARTED: u16 = 0x0200;
    pub const PROCESS_EXITED: u16 = 0x0201;
    pub const SIGNAL: u16 = 0x0202;
    pub const SESSION_START_FAILED: u16 = 0x0203;

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
    pub const ACK_JOURNAL: u16 = 0x0007;

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

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct OperationControlPayload {
    pub operation_sequence: u64,
    pub command_id: Vec<u8>,
    pub command_envelope: Vec<u8>,
    pub effect: Vec<u8>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum CommandOutcome {
    Succeeded,
    Failed,
    Rejected,
    Ambiguous,
}

impl CommandOutcome {
    pub const fn wire_code(self) -> u64 {
        match self {
            Self::Succeeded => 1,
            Self::Failed => 2,
            Self::Rejected => 3,
            Self::Ambiguous => 4,
        }
    }
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

pub fn encode_operation_control_payload(
    message_type: u16,
    operation_sequence: u64,
    command_id: &[u8],
    command_envelope: &[u8],
    effect: &[u8],
) -> Result<Vec<u8>, EncodeError> {
    validate_operation_identity(operation_sequence, command_id, command_envelope)?;
    validate_operation_effect(message_type, effect)?;
    let payload_length = 8_usize
        .checked_add(2)
        .and_then(|length| length.checked_add(command_id.len()))
        .and_then(|length| length.checked_add(4))
        .and_then(|length| length.checked_add(command_envelope.len()))
        .and_then(|length| length.checked_add(effect.len()))
        .ok_or(EncodeError::PayloadTooLarge {
            actual: usize::MAX,
            maximum: MAX_PAYLOAD_LENGTH,
        })?;
    validate_payload_length(payload_length)?;
    let envelope_length = u32::try_from(command_envelope.len()).map_err(|_| {
        EncodeError::PayloadTooLarge {
            actual: command_envelope.len(),
            maximum: MAX_PAYLOAD_LENGTH,
        }
    })?;
    let mut payload = Vec::with_capacity(payload_length);
    payload.extend_from_slice(&operation_sequence.to_le_bytes());
    payload.extend_from_slice(&(command_id.len() as u16).to_le_bytes());
    payload.extend_from_slice(command_id);
    payload.extend_from_slice(&envelope_length.to_le_bytes());
    payload.extend_from_slice(command_envelope);
    payload.extend_from_slice(effect);
    Ok(payload)
}

pub fn decode_operation_control_payload(
    message_type: u16,
    payload: &[u8],
) -> Result<OperationControlPayload, EncodeError> {
    validate_payload_length(payload.len())?;
    if payload.len() < 10 {
        return Err(EncodeError::InvalidPayload(
            "operation control payload is truncated before the command ID",
        ));
    }
    let operation_sequence = u64::from_le_bytes(payload[0..8].try_into().unwrap());
    let command_id_length = usize::from(u16::from_le_bytes(payload[8..10].try_into().unwrap()));
    let command_id_end = 10_usize.checked_add(command_id_length).ok_or(
        EncodeError::InvalidPayload("operation control command ID length overflows"),
    )?;
    let envelope_length_end = command_id_end.checked_add(4).ok_or(
        EncodeError::InvalidPayload("operation control command envelope length overflows"),
    )?;
    if payload.len() < envelope_length_end {
        return Err(EncodeError::InvalidPayload(
            "operation control payload is truncated before the command envelope",
        ));
    }
    let envelope_length = u32::from_le_bytes(
        payload[command_id_end..envelope_length_end]
            .try_into()
            .unwrap(),
    ) as usize;
    let envelope_end = envelope_length_end.checked_add(envelope_length).ok_or(
        EncodeError::InvalidPayload("operation control command envelope length overflows"),
    )?;
    if payload.len() < envelope_end {
        return Err(EncodeError::InvalidPayload(
            "operation control command envelope is truncated",
        ));
    }
    let command_id = &payload[10..command_id_end];
    let command_envelope = &payload[envelope_length_end..envelope_end];
    let effect = &payload[envelope_end..];
    validate_operation_identity(operation_sequence, command_id, command_envelope)?;
    validate_operation_effect(message_type, effect)?;
    Ok(OperationControlPayload {
        operation_sequence,
        command_id: command_id.to_vec(),
        command_envelope: command_envelope.to_vec(),
        effect: effect.to_vec(),
    })
}

pub fn journal_ack_payload(event_id: u64) -> Result<[u8; 8], EncodeError> {
    if event_id == 0 {
        return Err(EncodeError::InvalidPayload(
            "journal acknowledgement event ID must be nonzero",
        ));
    }
    Ok(event_id.to_le_bytes())
}

pub fn decode_journal_ack_payload(payload: &[u8]) -> Result<u64, EncodeError> {
    if payload.len() != 8 {
        return Err(EncodeError::InvalidPayload(
            "journal acknowledgement payload must be 8 bytes",
        ));
    }
    let event_id = u64::from_le_bytes(payload.try_into().unwrap());
    journal_ack_payload(event_id)?;
    Ok(event_id)
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

pub fn encode_session_start_failed(
    event_id: u64,
    command_id: &str,
    diagnostic: &str,
    omitted_byte_count: u64,
) -> Result<Vec<u8>, EncodeError> {
    validate_command_id(command_id.as_bytes())?;
    if diagnostic.len() > MAX_START_DIAGNOSTIC_BYTES {
        return Err(EncodeError::InvalidPayload(
            "session start failure diagnostic exceeds 1 MiB",
        ));
    }
    let mut encoded = event_prefix(event_id, event_type::SESSION_START_FAILED);
    cbor_array(&mut encoded, 3);
    cbor_text(&mut encoded, command_id);
    cbor_text(&mut encoded, diagnostic);
    cbor_unsigned(&mut encoded, omitted_byte_count);
    Ok(encoded)
}

pub fn session_start_failed_payload(
    command_id: &str,
    diagnostic: &str,
    omitted_byte_count: u64,
) -> Result<Vec<u8>, EncodeError> {
    encode_session_start_failed(1, command_id, diagnostic, omitted_byte_count)?;
    let mut payload = Vec::with_capacity(2 + command_id.len() + 8 + diagnostic.len());
    payload.extend_from_slice(&(command_id.len() as u16).to_le_bytes());
    payload.extend_from_slice(command_id.as_bytes());
    payload.extend_from_slice(&omitted_byte_count.to_le_bytes());
    payload.extend_from_slice(diagnostic.as_bytes());
    Ok(payload)
}

pub fn bound_start_diagnostic(diagnostic: &str) -> (String, u64) {
    if diagnostic.len() <= MAX_START_DIAGNOSTIC_BYTES {
        return (diagnostic.to_owned(), 0);
    }
    let mut prefix_end = START_DIAGNOSTIC_PREFIX_BYTES;
    while !diagnostic.is_char_boundary(prefix_end) {
        prefix_end -= 1;
    }
    let mut suffix_start = diagnostic.len() - START_DIAGNOSTIC_SUFFIX_BYTES;
    while !diagnostic.is_char_boundary(suffix_start) {
        suffix_start += 1;
    }
    let mut bounded = String::with_capacity(MAX_START_DIAGNOSTIC_BYTES);
    bounded.push_str(&diagnostic[..prefix_end]);
    bounded.push_str(&diagnostic[suffix_start..]);
    let omitted = diagnostic.len() - bounded.len();
    (bounded, omitted as u64)
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

pub fn encode_command_accepted(
    event_id: u64,
    operation_sequence: u64,
    command_envelope: &[u8],
) -> Result<Vec<u8>, EncodeError> {
    validate_operation_sequence(operation_sequence)?;
    validate_command_envelope(command_envelope)?;
    let mut encoded = event_prefix(event_id, event_type::COMMAND_ACCEPTED);
    cbor_array(&mut encoded, 2);
    cbor_unsigned(&mut encoded, operation_sequence);
    cbor_bytes(&mut encoded, command_envelope);
    Ok(encoded)
}

pub fn encode_command_result(
    event_id: u64,
    operation_sequence: u64,
    command_id: &[u8],
    outcome: CommandOutcome,
    detail: &str,
) -> Result<Vec<u8>, EncodeError> {
    validate_operation_sequence(operation_sequence)?;
    validate_command_id(command_id)?;
    if detail.len() > 4096 {
        return Err(EncodeError::InvalidPayload(
            "command result detail exceeds 4096 bytes",
        ));
    }
    if outcome == CommandOutcome::Succeeded && !detail.is_empty() {
        return Err(EncodeError::InvalidPayload(
            "successful command result detail must be empty",
        ));
    }
    let mut encoded = event_prefix(event_id, event_type::COMMAND_RESULT);
    cbor_array(&mut encoded, 4);
    cbor_unsigned(&mut encoded, operation_sequence);
    cbor_bytes(&mut encoded, command_id);
    cbor_unsigned(&mut encoded, outcome.wire_code());
    cbor_text(&mut encoded, detail);
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

fn validate_operation_identity(
    operation_sequence: u64,
    command_id: &[u8],
    command_envelope: &[u8],
) -> Result<(), EncodeError> {
    validate_operation_sequence(operation_sequence)?;
    validate_command_id(command_id)?;
    validate_command_envelope(command_envelope)
}

fn validate_operation_sequence(operation_sequence: u64) -> Result<(), EncodeError> {
    if operation_sequence == 0 {
        return Err(EncodeError::InvalidPayload(
            "operation sequence must be nonzero",
        ));
    }
    Ok(())
}

fn validate_command_id(command_id: &[u8]) -> Result<(), EncodeError> {
    if !valid_command_id(command_id) {
        return Err(EncodeError::InvalidPayload(
            "command ID must match [A-Za-z0-9][A-Za-z0-9._:-]{0,127}",
        ));
    }
    Ok(())
}

fn validate_command_envelope(command_envelope: &[u8]) -> Result<(), EncodeError> {
    if command_envelope.is_empty() {
        return Err(EncodeError::InvalidPayload(
            "command envelope must not be empty",
        ));
    }
    validate_payload_length(command_envelope.len())
}

pub fn valid_command_id(command_id: &[u8]) -> bool {
    if command_id.is_empty() || command_id.len() > 128 || !command_id[0].is_ascii_alphanumeric() {
        return false;
    }
    command_id[1..].iter().all(|byte| {
        byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b':' | b'-')
    })
}

fn validate_operation_effect(message_type: u16, effect: &[u8]) -> Result<(), EncodeError> {
    let valid = match message_type {
        control_message::INPUT => effect.len() >= 16,
        control_message::RESIZE => {
            effect.len() == 8
                && valid_terminal_dimensions(
                    u32::from_le_bytes(effect[0..4].try_into().unwrap()),
                    u32::from_le_bytes(effect[4..8].try_into().unwrap()),
                )
        }
        control_message::SIGNAL => {
            effect.len() == 8
                && u16::from_le_bytes(effect[2..4].try_into().unwrap()) == 0
                && valid_signal(
                    u16::from_le_bytes(effect[0..2].try_into().unwrap()),
                    i32::from_le_bytes(effect[4..8].try_into().unwrap()),
                )
        }
        control_message::TERMINATE => {
            effect.len() == 8
                && u16::from_le_bytes(effect[0..2].try_into().unwrap()) <= 1
                && u16::from_le_bytes(effect[2..4].try_into().unwrap()) == 0
        }
        _ => {
            return Err(EncodeError::InvalidPayload(
                "operation control message type is unsupported",
            ));
        }
    };
    if !valid {
        return Err(EncodeError::InvalidPayload(
            "operation control effect payload is invalid",
        ));
    }
    Ok(())
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
    fn checked_in_idempotent_control_fixture_is_stable() {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("protocol/fixtures/control-idempotency-v2.bin");
        assert_eq!(std::fs::read(path).unwrap(), protocol_fixture::control_idempotency_v2());
    }

    #[test]
    fn checked_in_command_event_fixture_is_stable() {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("protocol/fixtures/command-events-v1.hex");
        assert_eq!(
            std::fs::read_to_string(path).unwrap(),
            protocol_fixture::command_events_hex(),
        );
    }

    #[test]
    fn checked_in_start_outcome_fixture_is_shared_and_stable() {
        let native = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("protocol/fixtures/start-outcomes-v1.hex");
        let agent = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../agent-protocol/protocol/fixtures/start-outcomes-v1.hex");
        let generated = protocol_fixture::start_outcomes_hex();
        assert_eq!(std::fs::read_to_string(native).unwrap(), generated);
        assert_eq!(std::fs::read_to_string(agent).unwrap(), generated);
    }

    #[test]
    fn schema_two_operation_controls_round_trip_owned_identity_and_effects() {
        let input_id = [0x5a; 16];
        let cases = [
            (
                control_message::INPUT,
                [input_id.as_slice(), b"terminal input"].concat(),
            ),
            (control_message::RESIZE, [180_u32.to_le_bytes(), 50_u32.to_le_bytes()].concat()),
            (
                control_message::SIGNAL,
                concat_slices(&[
                    &1_u16.to_le_bytes(),
                    &0_u16.to_le_bytes(),
                    &(-1_i32).to_le_bytes(),
                ]),
            ),
            (
                control_message::TERMINATE,
                concat_slices(&[
                    &0_u16.to_le_bytes(),
                    &0_u16.to_le_bytes(),
                    &250_u32.to_le_bytes(),
                ]),
            ),
        ];

        for (index, (message_type, effect)) in cases.into_iter().enumerate() {
            let sequence = (i64::MAX as u64) + 1 + index as u64;
            let command_id = format!("command.{index}");
            let envelope = [0x84, 0x01, 0x02, 0x03, 0x66, b'f', b'u', b't', b'u', b'r', b'e'];
            let mut encoded = encode_operation_control_payload(
                message_type,
                sequence,
                command_id.as_bytes(),
                &envelope,
                &effect,
            )
            .unwrap();

            let decoded = decode_operation_control_payload(message_type, &encoded).unwrap();
            encoded.fill(0);

            assert_eq!(decoded.operation_sequence, sequence);
            assert_eq!(decoded.command_id, command_id.as_bytes());
            assert_eq!(decoded.command_envelope, envelope);
            assert_eq!(decoded.effect, effect);
        }
    }

    #[test]
    fn schema_two_operation_controls_reject_invalid_common_fields() {
        let effect = [0_u8; 16];
        assert_invalid_operation_payload(
            control_message::INPUT,
            0,
            b"command",
            &[0x80],
            &effect,
            "operation sequence must be nonzero",
        );
        for command_id in [b"".as_slice(), b" unsafe", b"slash/not-safe"] {
            assert_invalid_operation_payload(
                control_message::INPUT,
                1,
                command_id,
                &[0x80],
                &effect,
                "command ID",
            );
        }
        let oversized_command_id = vec![b'a'; 129];
        assert_invalid_operation_payload(
            control_message::INPUT,
            1,
            &oversized_command_id,
            &[0x80],
            &effect,
            "command ID",
        );
        assert_invalid_operation_payload(
            control_message::INPUT,
            1,
            b"command",
            &[],
            &effect,
            "command envelope must not be empty",
        );
    }

    #[test]
    fn schema_two_operation_controls_reject_truncated_and_oversized_envelopes() {
        let mut truncated = operation_prefix(1, b"command", 3);
        truncated.extend_from_slice(&[0x80, 0x81]);
        assert!(decode_operation_control_payload(control_message::INPUT, &truncated)
            .unwrap_err()
            .to_string()
            .contains("command envelope"));

        let oversized = vec![0_u8; MAX_PAYLOAD_LENGTH + 1];
        assert!(matches!(
            decode_operation_control_payload(control_message::INPUT, &oversized),
            Err(EncodeError::PayloadTooLarge { .. })
        ));

        let mut declared_oversized = operation_prefix(1, b"command", u32::MAX);
        declared_oversized.push(0x80);
        assert!(decode_operation_control_payload(control_message::INPUT, &declared_oversized)
            .unwrap_err()
            .to_string()
            .contains("command envelope"));
    }

    #[test]
    fn schema_two_operation_controls_validate_each_effect_shape() {
        let invalid_cases = [
            (control_message::INPUT, vec![0_u8; 15]),
            (control_message::RESIZE, [0_u32.to_le_bytes(), 50_u32.to_le_bytes()].concat()),
            (
                control_message::SIGNAL,
                concat_slices(&[
                    &1_u16.to_le_bytes(),
                    &1_u16.to_le_bytes(),
                    &(-1_i32).to_le_bytes(),
                ]),
            ),
            (
                control_message::TERMINATE,
                concat_slices(&[
                    &2_u16.to_le_bytes(),
                    &0_u16.to_le_bytes(),
                    &0_u32.to_le_bytes(),
                ]),
            ),
        ];
        for (message_type, effect) in invalid_cases {
            assert_invalid_operation_payload(
                message_type,
                1,
                b"command",
                &[0x80],
                &effect,
                "effect payload",
            );
        }
    }

    #[test]
    fn acknowledgement_watermarks_are_nonzero_exact_unsigned_values() {
        let watermark = (i64::MAX as u64) + 1;
        let encoded = journal_ack_payload(watermark).unwrap();
        assert_eq!(decode_journal_ack_payload(&encoded).unwrap(), watermark);
        assert!(journal_ack_payload(0).is_err());
        assert!(decode_journal_ack_payload(&0_u64.to_le_bytes()).is_err());
        assert!(decode_journal_ack_payload(&encoded[..7]).is_err());
    }

    #[test]
    fn command_events_encode_exact_envelopes_and_frozen_outcomes() {
        let sequence = (i64::MAX as u64) + 7;
        let envelope = [0x84, 0x01, 0x02, 0x03, 0x66, b'f', b'u', b't', b'u', b'r', b'e'];
        assert_eq!(
            encode_command_accepted(1, sequence, &envelope).unwrap(),
            [
                0x83, 0x01, 0x01, 0x82, 0x1b, 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x06, 0x4b, 0x84, 0x01, 0x02, 0x03, 0x66, b'f', b'u', b't', b'u', b'r', b'e',
            ],
        );
        assert_eq!(CommandOutcome::Succeeded.wire_code(), 1);
        assert_eq!(CommandOutcome::Failed.wire_code(), 2);
        assert_eq!(CommandOutcome::Rejected.wire_code(), 3);
        assert_eq!(CommandOutcome::Ambiguous.wire_code(), 4);
        assert_eq!(
            encode_command_result(2, sequence, b"command.7", CommandOutcome::Succeeded, "").unwrap(),
            [
                0x83, 0x02, 0x02, 0x84, 0x1b, 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x06, 0x49, b'c', b'o', b'm', b'm', b'a', b'n', b'd', b'.', b'7', 0x01,
                0x60,
            ],
        );
    }

    #[test]
    fn command_events_reject_invalid_identity_and_detail() {
        assert!(encode_command_accepted(1, 0, &[0x80]).is_err());
        assert!(encode_command_accepted(1, 1, &[]).is_err());
        assert!(encode_command_result(2, 0, b"command", CommandOutcome::Failed, "failed").is_err());
        assert!(encode_command_result(2, 1, b"bad/id", CommandOutcome::Failed, "failed").is_err());
        assert!(encode_command_result(2, 1, b"command", CommandOutcome::Succeeded, "detail").is_err());
        assert!(encode_command_result(
            2,
            1,
            b"command",
            CommandOutcome::Failed,
            &"x".repeat(4097),
        )
        .is_err());
    }

    #[test]
    fn session_start_failure_has_a_frozen_lifecycle_encoding() {
        assert_eq!(event_type::SESSION_START_FAILED, 0x0203);
        assert_eq!(
            encode_session_start_failed(2, "command.start", "exec failed", 0).unwrap(),
            [
                0x83, 0x02, 0x19, 0x02, 0x03, 0x83, 0x6d, b'c', b'o', b'm', b'm', b'a', b'n',
                b'd', b'.', b's', b't', b'a', b'r', b't', 0x6b, b'e', b'x', b'e', b'c', b' ',
                b'f', b'a', b'i', b'l', b'e', b'd', 0x00,
            ],
        );
    }

    #[test]
    fn session_start_failure_validates_identity_and_diagnostic_bound() {
        for command_id in ["", " unsafe", "slash/not-safe"] {
            assert!(encode_session_start_failed(1, command_id, "failed", 0).is_err());
        }
        let oversized_command_id = "a".repeat(129);
        assert!(encode_session_start_failed(1, &oversized_command_id, "failed", 0).is_err());
        let oversized_diagnostic = "x".repeat(MAX_START_DIAGNOSTIC_BYTES + 1);
        assert!(
            encode_session_start_failed(1, "command.start", &oversized_diagnostic, 1).is_err()
        );
    }

    #[test]
    fn bounds_start_diagnostics_by_utf8_bytes_without_splitting_characters() {
        let ascii = "a".repeat(MAX_START_DIAGNOSTIC_BYTES + 17);
        let (bounded, omitted) = bound_start_diagnostic(&ascii);
        assert_eq!(bounded.len(), MAX_START_DIAGNOSTIC_BYTES);
        assert_eq!(omitted, 17);
        assert_eq!(
            &bounded[..START_DIAGNOSTIC_PREFIX_BYTES],
            &ascii[..START_DIAGNOSTIC_PREFIX_BYTES]
        );
        assert_eq!(
            &bounded[START_DIAGNOSTIC_PREFIX_BYTES..],
            &ascii[ascii.len() - START_DIAGNOSTIC_SUFFIX_BYTES..]
        );

        let unicode = format!(
            "{}é{}é",
            "p".repeat(START_DIAGNOSTIC_PREFIX_BYTES - 1),
            "s".repeat(START_DIAGNOSTIC_SUFFIX_BYTES)
        );
        let (bounded, omitted) = bound_start_diagnostic(&unicode);
        assert!(bounded.len() <= MAX_START_DIAGNOSTIC_BYTES);
        assert_eq!(omitted, (unicode.len() - bounded.len()) as u64);
        assert!(bounded.starts_with(&"p".repeat(START_DIAGNOSTIC_PREFIX_BYTES - 1)));
        assert!(bounded.ends_with('é'));
    }

    #[test]
    fn metadata_fixture_contains_only_session_manifest_fields() {
        let metadata = include_str!("../protocol/fixtures/metadata-v1.json");
        for required in [
            "\"sessionId\"",
            "\"childPid\"",
            "\"currentCols\"",
            "\"sandbox\"",
            "\"control\"",
        ] {
            assert!(metadata.contains(required), "metadata fixture lacks {required}");
        }
        for removed in [
            "journalId",
            "state",
            "activeSegment",
            "oldestAvailableEventId",
            "latestEventId",
        ] {
            assert!(!metadata.contains(removed), "metadata fixture contains {removed}");
        }
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

    fn operation_prefix(sequence: u64, command_id: &[u8], envelope_length: u32) -> Vec<u8> {
        let mut payload = Vec::new();
        payload.extend_from_slice(&sequence.to_le_bytes());
        payload.extend_from_slice(&(command_id.len() as u16).to_le_bytes());
        payload.extend_from_slice(command_id);
        payload.extend_from_slice(&envelope_length.to_le_bytes());
        payload
    }

    fn concat_slices(parts: &[&[u8]]) -> Vec<u8> {
        parts.iter().flat_map(|part| part.iter().copied()).collect()
    }

    fn assert_invalid_operation_payload(
        message_type: u16,
        sequence: u64,
        command_id: &[u8],
        envelope: &[u8],
        effect: &[u8],
        expected: &str,
    ) {
        let error = encode_operation_control_payload(
            message_type,
            sequence,
            command_id,
            envelope,
            effect,
        )
        .unwrap_err();
        assert!(error.to_string().contains(expected), "unexpected error: {error}");
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

    pub fn control_idempotency_v2() -> Vec<u8> {
        let input_id = [
            0xf0, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9, 0xfa, 0xfb, 0xfc, 0xfd,
            0xfe, 0xff,
        ];
        let input_bytes = b"echo v2\n";
        let command_ids = ["command.input", "command.resize", "command.signal", "command.terminate"];
        let envelopes = command_envelopes(&command_ids, input_id, input_bytes);
        let effects = [
            [input_id.as_slice(), input_bytes].concat(),
            [200_u32.to_le_bytes(), 60_u32.to_le_bytes()].concat(),
            concat_parts(&[
                &1_u16.to_le_bytes(),
                &0_u16.to_le_bytes(),
                &(-1_i32).to_le_bytes(),
            ]),
            concat_parts(&[
                &0_u16.to_le_bytes(),
                &0_u16.to_le_bytes(),
                &500_u32.to_le_bytes(),
            ]),
        ];
        let message_types = [
            control_message::INPUT,
            control_message::RESIZE,
            control_message::SIGNAL,
            control_message::TERMINATE,
        ];
        let mut frames = Vec::new();
        for index in 0..message_types.len() {
            let operation_sequence = (i64::MAX as u64) + 1 + index as u64;
            let payload = encode_operation_control_payload(
                message_types[index],
                operation_sequence,
                command_ids[index].as_bytes(),
                &envelopes[index],
                &effects[index],
            )
            .unwrap();
            frames.push(
                encode_control_frame(ControlFrame {
                    message_type: message_types[index],
                    payload_schema_version: 2,
                    flags: 0,
                    request_id: 70 + index as u64,
                    payload: &payload,
                })
                .unwrap(),
            );
        }
        let acknowledgement = journal_ack_payload((i64::MAX as u64) + 5).unwrap();
        frames.push(
            encode_control_frame(ControlFrame {
                message_type: control_message::ACK_JOURNAL,
                payload_schema_version: 1,
                flags: 0,
                request_id: 74,
                payload: &acknowledgement,
            })
            .unwrap(),
        );
        frames.concat()
    }

    pub fn command_events_hex() -> String {
        format_hex_records(&command_event_records())
    }

    pub fn start_outcomes_hex() -> String {
        format_hex_records(&start_outcome_records())
    }

    fn journal_records() -> Vec<Vec<u8>> {
        vec![
            encode_pty_output(1, &[0, 0x1b, 0xff]).unwrap(),
            encode_pty_resize(2, 180, 50).unwrap(),
            encode_pty_input(3, "00010203-0405-0607-0809-0a0b0c0d0e0f", &[0, 0xff]).unwrap(),
            encode_process_exited(4, 0),
        ]
    }

    fn command_event_records() -> Vec<Vec<u8>> {
        let input_id = [
            0xf0, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9, 0xfa, 0xfb, 0xfc, 0xfd,
            0xfe, 0xff,
        ];
        let command_id = "command.input";
        let envelope = command_envelopes(&[command_id, "resize", "signal", "terminate"], input_id, b"echo v2\n")
            .remove(0);
        let operation_sequence = (i64::MAX as u64) + 1;
        vec![
            encode_command_accepted(1, operation_sequence, &envelope).unwrap(),
            encode_command_result(
                2,
                operation_sequence,
                command_id.as_bytes(),
                CommandOutcome::Succeeded,
                "",
            )
            .unwrap(),
        ]
    }

    fn start_outcome_records() -> Vec<Vec<u8>> {
        vec![
            encode_process_started(1, 4242).unwrap(),
            encode_session_start_failed(2, "command.start", "exec failed", 17).unwrap(),
        ]
    }

    fn command_envelopes(
        command_ids: &[&str; 4],
        input_id: [u8; 16],
        input_bytes: &[u8],
    ) -> Vec<Vec<u8>> {
        let mut input = command_envelope_prefix(0x8101, command_ids[0]);
        cbor_bytes(&mut input, &input_id);
        cbor_bytes(&mut input, input_bytes);
        cbor_text(&mut input, "future");

        let mut resize = command_envelope_prefix(0x8102, command_ids[1]);
        cbor_unsigned(&mut resize, 200);
        cbor_unsigned(&mut resize, 60);
        cbor_text(&mut resize, "future");

        let mut signal = command_envelope_prefix(0x8103, command_ids[2]);
        cbor_unsigned(&mut signal, 1);
        cbor_signed(&mut signal, -1);
        cbor_text(&mut signal, "future");

        let mut terminate = command_envelope_prefix(0x8104, command_ids[3]);
        cbor_unsigned(&mut terminate, 0);
        cbor_unsigned(&mut terminate, 500);
        cbor_text(&mut terminate, "future");
        vec![input, resize, signal, terminate]
    }

    fn command_envelope_prefix(message_type: u16, command_id: &str) -> Vec<u8> {
        let mut envelope = Vec::new();
        cbor_array(&mut envelope, 6);
        cbor_unsigned(&mut envelope, u64::from(message_type));
        cbor_text(&mut envelope, command_id);
        cbor_text(&mut envelope, "session-v2");
        envelope
    }

    fn concat_parts(parts: &[&[u8]]) -> Vec<u8> {
        let length = parts.iter().map(|part| part.len()).sum();
        let mut bytes = Vec::with_capacity(length);
        for part in parts {
            bytes.extend_from_slice(part);
        }
        bytes
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
