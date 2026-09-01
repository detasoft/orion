use std::fmt::{Display, Formatter};

pub const JOURNAL_VERSION: u16 = 1;
pub const CONTROL_VERSION: u16 = 1;
pub const SEGMENT_HEADER_LENGTH: usize = 64;
pub const BLOCK_HEADER_LENGTH: usize = 64;
pub const RECORD_HEADER_LENGTH: usize = 32;
pub const CONTROL_HEADER_LENGTH: usize = 32;
pub const MAX_PAYLOAD_LENGTH: usize = 16 * 1024 * 1024;

pub const SEGMENT_MAGIC: &[u8; 8] = b"ORJSEG01";
pub const BLOCK_MAGIC: &[u8; 8] = b"ORJBLK01";
pub const RECORD_MAGIC: &[u8; 4] = b"ORJR";
pub const CONTROL_MAGIC: &[u8; 4] = b"ORCT";

pub mod codec {
    pub const NONE: u16 = 0;
    pub const ZSTD: u16 = 1;
}

pub mod block_flags {
    pub const FINAL: u16 = 0x0001;
}

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
pub struct BlockHeader {
    pub codec: u16,
    pub flags: u16,
    pub block_sequence: u64,
    pub first_timestamp: u64,
    pub last_timestamp: u64,
    pub uncompressed_length: u32,
    pub stored_length: u32,
    pub record_count: u32,
    pub payload_crc32c: u32,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Record<'a> {
    pub event_type: u16,
    pub payload_schema_version: u16,
    pub flags: u32,
    pub timestamp: u64,
    pub payload: &'a [u8],
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
}

impl Display for EncodeError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::PayloadTooLarge { actual, maximum } => {
                write!(formatter, "payload length {actual} exceeds maximum {maximum}")
            }
        }
    }
}

impl std::error::Error for EncodeError {}

pub fn encode_segment_header(
    journal_id: [u8; 16],
    segment_sequence: u64,
    created_at_epoch_millis: u64,
) -> [u8; SEGMENT_HEADER_LENGTH] {
    let mut header = [0_u8; SEGMENT_HEADER_LENGTH];
    header[0..8].copy_from_slice(SEGMENT_MAGIC);
    put_u16(&mut header[8..10], JOURNAL_VERSION);
    put_u16(&mut header[10..12], SEGMENT_HEADER_LENGTH as u16);
    put_u32(&mut header[12..16], 0);
    header[16..32].copy_from_slice(&journal_id);
    put_u64(&mut header[32..40], segment_sequence);
    put_u64(&mut header[40..48], created_at_epoch_millis);
    let checksum = crc32c(&header[..60]);
    put_u32(&mut header[60..64], checksum);
    header
}

pub fn encode_block_header(block: BlockHeader) -> [u8; BLOCK_HEADER_LENGTH] {
    let mut header = [0_u8; BLOCK_HEADER_LENGTH];
    header[0..8].copy_from_slice(BLOCK_MAGIC);
    put_u16(&mut header[8..10], JOURNAL_VERSION);
    put_u16(&mut header[10..12], BLOCK_HEADER_LENGTH as u16);
    put_u16(&mut header[12..14], block.codec);
    put_u16(&mut header[14..16], block.flags);
    put_u64(&mut header[16..24], block.block_sequence);
    put_u64(&mut header[24..32], block.first_timestamp);
    put_u64(&mut header[32..40], block.last_timestamp);
    put_u32(&mut header[40..44], block.uncompressed_length);
    put_u32(&mut header[44..48], block.stored_length);
    put_u32(&mut header[48..52], block.record_count);
    put_u32(&mut header[56..60], block.payload_crc32c);
    let checksum = crc32c(&header[..60]);
    put_u32(&mut header[60..64], checksum);
    header
}

pub fn encode_record(record: Record<'_>) -> Result<Vec<u8>, EncodeError> {
    validate_payload_length(record.payload.len())?;
    let mut encoded = vec![0_u8; RECORD_HEADER_LENGTH + record.payload.len()];
    encoded[0..4].copy_from_slice(RECORD_MAGIC);
    put_u16(&mut encoded[4..6], JOURNAL_VERSION);
    put_u16(&mut encoded[6..8], RECORD_HEADER_LENGTH as u16);
    put_u16(&mut encoded[8..10], record.event_type);
    put_u16(&mut encoded[10..12], record.payload_schema_version);
    put_u32(&mut encoded[12..16], record.flags);
    put_u64(&mut encoded[16..24], record.timestamp);
    put_u32(&mut encoded[24..28], record.payload.len() as u32);
    put_u32(&mut encoded[28..32], crc32c(record.payload));
    encoded[RECORD_HEADER_LENGTH..].copy_from_slice(record.payload);
    Ok(encoded)
}

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
    fn crc32c_matches_standard_check_value() {
        assert_eq!(crc32c(b"123456789"), 0xe306_9283);
    }

    #[test]
    fn record_preserves_raw_payload_bytes() {
        let payload = [0x1b, b'[', b'3', b'1', b'm', 0xff, b'\r', b'\n'];
        let encoded = encode_record(Record {
            event_type: event_type::PTY_OUTPUT,
            payload_schema_version: 1,
            flags: 0,
            timestamp: 1,
            payload: &payload,
        })
        .unwrap();

        assert_eq!(&encoded[RECORD_HEADER_LENGTH..], payload);
    }

    #[test]
    fn checked_in_journal_fixture_is_stable() {
        assert_eq!(
            include_bytes!("../protocol/fixtures/journal-v1.bin").as_slice(),
            protocol_fixture::journal().as_slice()
        );
    }

    #[test]
    fn checked_in_control_fixture_is_stable() {
        assert_eq!(
            include_bytes!("../protocol/fixtures/control-v1.bin").as_slice(),
            protocol_fixture::control().as_slice()
        );
    }

    #[test]
    fn checked_in_crash_tail_fixtures_are_stable() {
        assert_eq!(
            include_bytes!("../protocol/fixtures/truncated-record-v1.bin").as_slice(),
            protocol_fixture::truncated_record().as_slice()
        );
        assert_eq!(
            include_bytes!("../protocol/fixtures/truncated-zstd-block-v1.bin").as_slice(),
            protocol_fixture::truncated_zstd_block().as_slice()
        );
    }

    #[test]
    fn metadata_fixture_declares_the_v1_contract() {
        let metadata = include_str!("../protocol/fixtures/metadata-v1.json");

        assert!(metadata.contains("\"metadataVersion\": 1"));
        assert!(metadata.contains("\"journalFormatVersion\": 1"));
        assert!(metadata.contains("\"controlProtocolVersion\": 1"));
        assert!(metadata.contains("\"oldestAvailableTimestamp\": 1"));
        assert!(metadata.contains("\"latestTimestamp\": 4"));
    }
}

pub mod protocol_fixture {
    use super::*;

    const JOURNAL_ID: [u8; 16] = [
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d,
        0x0e, 0x0f,
    ];
    const CREATED_AT_EPOCH_MILLIS: u64 = 1_750_000_000_000;

    pub fn journal() -> Vec<u8> {
        let records = journal_records();
        let payload = concatenate(&records);
        let block = encode_block_header(BlockHeader {
            codec: codec::NONE,
            flags: block_flags::FINAL,
            block_sequence: 0,
            first_timestamp: 1,
            last_timestamp: 4,
            uncompressed_length: payload.len() as u32,
            stored_length: payload.len() as u32,
            record_count: records.len() as u32,
            payload_crc32c: crc32c(&payload),
        });

        let mut fixture = encode_segment_header(JOURNAL_ID, 1, CREATED_AT_EPOCH_MILLIS).to_vec();
        fixture.extend_from_slice(&block);
        fixture.extend_from_slice(&payload);
        fixture
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

        let mut fixture = request;
        fixture.extend_from_slice(&response);
        fixture
    }

    pub fn truncated_record() -> Vec<u8> {
        let records = journal_records();
        let declared_payload = concatenate(&records[..2]);
        let block = encode_block_header(BlockHeader {
            codec: codec::NONE,
            flags: block_flags::FINAL,
            block_sequence: 0,
            first_timestamp: 1,
            last_timestamp: 2,
            uncompressed_length: declared_payload.len() as u32,
            stored_length: declared_payload.len() as u32,
            record_count: 2,
            payload_crc32c: crc32c(&declared_payload),
        });

        let mut fixture = encode_segment_header(JOURNAL_ID, 1, CREATED_AT_EPOCH_MILLIS).to_vec();
        fixture.extend_from_slice(&block);
        fixture.extend_from_slice(&records[0]);
        fixture.extend_from_slice(&records[1][..12]);
        fixture
    }

    pub fn truncated_zstd_block() -> Vec<u8> {
        let complete_payload = [0_u8; 32];
        let block = encode_block_header(BlockHeader {
            codec: codec::ZSTD,
            flags: block_flags::FINAL,
            block_sequence: 0,
            first_timestamp: 1,
            last_timestamp: 1,
            uncompressed_length: 64,
            stored_length: complete_payload.len() as u32,
            record_count: 1,
            payload_crc32c: crc32c(&complete_payload),
        });

        let mut fixture = encode_segment_header(JOURNAL_ID, 1, CREATED_AT_EPOCH_MILLIS).to_vec();
        fixture.extend_from_slice(&block);
        fixture.extend_from_slice(&[0x28, 0xb5, 0x2f, 0xfd, 0x00, 0x00, 0x00]);
        fixture
    }

    fn journal_records() -> Vec<Vec<u8>> {
        let output = [0x1b, b'[', b'3', b'1', b'm', 0xff, b'\r', b'\n'];
        let unknown = [0xde, 0xad, 0xbe, 0xef];
        let resize = pty_resize_payload(180, 50);
        let exited = process_exited_payload(0, -1);

        vec![
            encode_record(Record {
                event_type: event_type::PTY_OUTPUT,
                payload_schema_version: 1,
                flags: 0,
                timestamp: 1,
                payload: &output,
            })
            .unwrap(),
            encode_record(Record {
                event_type: 0x7ffe,
                payload_schema_version: 9,
                flags: 0,
                timestamp: 2,
                payload: &unknown,
            })
            .unwrap(),
            encode_record(Record {
                event_type: event_type::PTY_RESIZE,
                payload_schema_version: 1,
                flags: 0,
                timestamp: 3,
                payload: &resize,
            })
            .unwrap(),
            encode_record(Record {
                event_type: event_type::PROCESS_EXITED,
                payload_schema_version: 1,
                flags: 0,
                timestamp: 4,
                payload: &exited,
            })
            .unwrap(),
        ]
    }

    fn concatenate(records: &[Vec<u8>]) -> Vec<u8> {
        let length = records.iter().map(Vec::len).sum();
        let mut payload = Vec::with_capacity(length);
        for record in records {
            payload.extend_from_slice(record);
        }
        payload
    }
}
