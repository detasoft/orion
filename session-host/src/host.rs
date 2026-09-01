use std::fmt::{Display, Formatter};
use std::io::{self, Read, Write};

use crate::journal::JournalError;
use crate::protocol::{
    self, CONTROL_HEADER_LENGTH, CONTROL_MAGIC, CONTROL_VERSION, ControlFrame, MAX_PAYLOAD_LENGTH,
};

pub const ERROR_INVALID_REQUEST: u32 = 1;
pub const ERROR_UNSUPPORTED_MESSAGE: u32 = 2;
pub const ERROR_UNSUPPORTED_SCHEMA: u32 = 3;
pub const ERROR_INVALID_STATE: u32 = 4;
pub const ERROR_IO: u32 = 5;
pub const ERROR_POLICY: u32 = 6;
pub const ERROR_PAYLOAD_TOO_LARGE: u32 = 7;

#[derive(Debug)]
pub enum HostError {
    Io(io::Error),
    Journal(JournalError),
    Protocol(String),
    InvalidOptions(String),
    Policy(String),
    Unsupported(String),
    Thread(String),
}

impl Display for HostError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Io(error) => write!(formatter, "host I/O error: {error}"),
            Self::Journal(error) => Display::fmt(error, formatter),
            Self::Protocol(message) => write!(formatter, "invalid control frame: {message}"),
            Self::InvalidOptions(message) => {
                write!(formatter, "invalid session options: {message}")
            }
            Self::Policy(message) => write!(formatter, "sandbox policy error: {message}"),
            Self::Unsupported(message) => formatter.write_str(message),
            Self::Thread(message) => write!(formatter, "host thread failed: {message}"),
        }
    }
}

impl std::error::Error for HostError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Io(error) => Some(error),
            Self::Journal(error) => Some(error),
            _ => None,
        }
    }
}

impl From<io::Error> for HostError {
    fn from(error: io::Error) -> Self {
        Self::Io(error)
    }
}

impl From<JournalError> for HostError {
    fn from(error: JournalError) -> Self {
        Self::Journal(error)
    }
}

#[derive(Debug, Eq, PartialEq)]
pub struct OwnedControlFrame {
    pub message_type: u16,
    pub payload_schema_version: u16,
    pub request_id: u64,
    pub payload: Vec<u8>,
}

pub fn read_control_frame(reader: &mut impl Read) -> Result<Option<OwnedControlFrame>, HostError> {
    let mut header = [0_u8; CONTROL_HEADER_LENGTH];
    loop {
        match reader.read(&mut header[..1]) {
            Ok(0) => return Ok(None),
            Ok(1) => break,
            Ok(_) => unreachable!(),
            Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
            Err(error) => return Err(error.into()),
        }
    }
    reader.read_exact(&mut header[1..])?;
    if &header[0..4] != CONTROL_MAGIC {
        return Err(HostError::Protocol("bad magic".to_owned()));
    }
    if u16_at(&header[4..6]) != CONTROL_VERSION {
        return Err(HostError::Protocol("unsupported version".to_owned()));
    }
    if usize::from(u16_at(&header[6..8])) != CONTROL_HEADER_LENGTH {
        return Err(HostError::Protocol("unsupported header length".to_owned()));
    }
    if u32_at(&header[12..16]) != 0 {
        return Err(HostError::Protocol("nonzero framing flags".to_owned()));
    }
    let payload_length = u32_at(&header[24..28]) as usize;
    if payload_length > MAX_PAYLOAD_LENGTH {
        return Err(HostError::Protocol(format!(
            "payload length {payload_length} exceeds {MAX_PAYLOAD_LENGTH}"
        )));
    }
    let mut payload = vec![0_u8; payload_length];
    reader.read_exact(&mut payload)?;
    if protocol::crc32c(&payload) != u32_at(&header[28..32]) {
        return Err(HostError::Protocol("payload checksum mismatch".to_owned()));
    }
    Ok(Some(OwnedControlFrame {
        message_type: u16_at(&header[8..10]),
        payload_schema_version: u16_at(&header[10..12]),
        request_id: u64_at(&header[16..24]),
        payload,
    }))
}

pub fn write_control_frame(
    writer: &mut impl Write,
    message_type: u16,
    request_id: u64,
    payload: &[u8],
) -> Result<(), HostError> {
    let encoded = protocol::encode_control_frame(ControlFrame {
        message_type,
        payload_schema_version: 1,
        flags: 0,
        request_id,
        payload,
    })
    .map_err(|error| HostError::Protocol(error.to_string()))?;
    writer.write_all(&encoded)?;
    Ok(())
}

pub fn timestamp_payload(timestamp: u64) -> [u8; 8] {
    timestamp.to_le_bytes()
}

pub fn error_payload(code: u32, detail: &str) -> Vec<u8> {
    let mut end = detail.len().min(4096);
    while !detail.is_char_boundary(end) {
        end -= 1;
    }
    let mut payload = Vec::with_capacity(4 + end);
    payload.extend_from_slice(&code.to_le_bytes());
    payload.extend_from_slice(&detail.as_bytes()[..end]);
    payload
}

pub fn signal_payload(kind: u16, platform_signal: i32) -> [u8; 8] {
    let mut payload = [0_u8; 8];
    payload[0..2].copy_from_slice(&kind.to_le_bytes());
    payload[4..8].copy_from_slice(&platform_signal.to_le_bytes());
    payload
}

fn u16_at(bytes: &[u8]) -> u16 {
    u16::from_le_bytes(bytes.try_into().unwrap())
}

pub fn u32_at(bytes: &[u8]) -> u32 {
    u32::from_le_bytes(bytes.try_into().unwrap())
}

pub fn i32_at(bytes: &[u8]) -> i32 {
    i32::from_le_bytes(bytes.try_into().unwrap())
}

fn u64_at(bytes: &[u8]) -> u64 {
    u64::from_le_bytes(bytes.try_into().unwrap())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::control_message;
    use std::io::Cursor;

    #[test]
    fn round_trips_a_binary_control_frame() {
        let payload = b"\0\xffinput";
        let mut bytes = Vec::new();
        write_control_frame(&mut bytes, control_message::INPUT, 41, payload).unwrap();

        let decoded = read_control_frame(&mut Cursor::new(bytes))
            .unwrap()
            .unwrap();
        assert_eq!(decoded.message_type, control_message::INPUT);
        assert_eq!(decoded.payload_schema_version, 1);
        assert_eq!(decoded.request_id, 41);
        assert_eq!(decoded.payload, payload);
    }

    #[test]
    fn rejects_a_corrupt_frame_without_scanning_for_another() {
        let mut bytes = Vec::new();
        write_control_frame(&mut bytes, control_message::STATUS, 7, b"payload").unwrap();
        *bytes.last_mut().unwrap() ^= 1;

        let error = read_control_frame(&mut Cursor::new(bytes)).unwrap_err();
        assert!(error.to_string().contains("checksum mismatch"));
    }

    #[test]
    fn truncates_error_detail_at_a_utf8_boundary() {
        let detail = format!("{}é", "a".repeat(4095));
        let payload = error_payload(ERROR_INVALID_REQUEST, &detail);

        assert_eq!(payload.len(), 4 + 4095);
        assert!(std::str::from_utf8(&payload[4..]).is_ok());
    }
}
