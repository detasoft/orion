use std::fs::OpenOptions;
use std::io::Read;
#[cfg(unix)]
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};

use crate::host::HostError;

pub(crate) const HANDLED_FS_RIGHTS: u64 = (1 << 17) - 1;
const MAX_POLICY_BYTES: u64 = 1024 * 1024;
const MAX_RULES: usize = 32_768;
const MAX_PATH_BYTES: usize = 4096;

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct CompiledPolicy {
    pub(crate) version: u64,
    pub(crate) handled_rights: u64,
    pub(crate) rules: Vec<CompiledRule>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct CompiledRule {
    pub(crate) path: PathBuf,
    pub(crate) rights: u64,
}

pub(crate) fn load(path: &Path) -> Result<CompiledPolicy, HostError> {
    let mut options = OpenOptions::new();
    options.read(true);
    #[cfg(unix)]
    options.custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC);
    let file = options
        .open(path)
        .map_err(|error| policy(format!("cannot open compiled policy: {error}")))?;
    let metadata = file
        .metadata()
        .map_err(|error| policy(format!("cannot inspect compiled policy: {error}")))?;
    if !metadata.is_file() {
        return Err(policy("compiled policy is not a regular file"));
    }
    if metadata.len() > MAX_POLICY_BYTES {
        return Err(policy("compiled policy exceeds the size limit"));
    }
    let mut bytes = Vec::with_capacity(metadata.len() as usize);
    file.take(MAX_POLICY_BYTES + 1)
        .read_to_end(&mut bytes)
        .map_err(|error| policy(format!("cannot read compiled policy: {error}")))?;
    if bytes.len() as u64 > MAX_POLICY_BYTES {
        return Err(policy("compiled policy exceeds the size limit"));
    }
    decode(&bytes)
}

fn decode(bytes: &[u8]) -> Result<CompiledPolicy, HostError> {
    let mut decoder = Decoder { bytes, offset: 0 };
    decoder.array(3, "top-level policy")?;
    let version = decoder.unsigned("version")?;
    if version != 1 {
        return Err(policy(format!("unsupported compiled policy version {version}")));
    }
    let handled_rights = decoder.unsigned("handledRights")?;
    if handled_rights != HANDLED_FS_RIGHTS {
        return Err(policy("compiled policy has an invalid handledRights mask"));
    }
    let count = decoder.array_length("rules")?;
    if count > MAX_RULES {
        return Err(policy("compiled policy has too many rules"));
    }
    let mut rules = Vec::with_capacity(count);
    let mut previous: Option<Vec<u8>> = None;
    for index in 0..count {
        decoder.array(2, "rule")?;
        let raw_path = decoder.text("rule path")?;
        if raw_path.len() > MAX_PATH_BYTES {
            return Err(policy(format!("rule {index} path exceeds the size limit")));
        }
        if let Some(before) = &previous
            && before.as_slice() >= raw_path
        {
            return Err(policy("compiled policy paths are duplicate or unsorted"));
        }
        let text = std::str::from_utf8(raw_path)
            .map_err(|_| policy(format!("rule {index} path is not valid UTF-8")))?;
        let path = PathBuf::from(text);
        if !normalized_absolute(text) {
            return Err(policy(format!("rule {index} path is not normalized and absolute")));
        }
        let rights = decoder.unsigned("rule rights")?;
        if rights == 0 || rights & !HANDLED_FS_RIGHTS != 0 {
            return Err(policy(format!("rule {index} has invalid rights")));
        }
        previous = Some(raw_path.to_vec());
        rules.push(CompiledRule { path, rights });
    }
    if decoder.offset != bytes.len() {
        return Err(policy("compiled policy has trailing data"));
    }
    Ok(CompiledPolicy {
        version,
        handled_rights,
        rules,
    })
}

fn normalized_absolute(path: &str) -> bool {
    if path == "/" {
        return true;
    }
    path.starts_with('/')
        && !path.contains('\0')
        && path
            .split('/')
            .skip(1)
            .all(|component| !component.is_empty() && component != "." && component != "..")
}

fn policy(message: impl Into<String>) -> HostError {
    HostError::Policy(message.into())
}

struct Decoder<'a> {
    bytes: &'a [u8],
    offset: usize,
}

impl<'a> Decoder<'a> {
    fn array(&mut self, expected: usize, field: &str) -> Result<(), HostError> {
        let actual = self.array_length(field)?;
        if actual != expected {
            return Err(policy(format!("{field} must contain {expected} items")));
        }
        Ok(())
    }

    fn array_length(&mut self, field: &str) -> Result<usize, HostError> {
        let value = self.argument(4, field)?;
        usize::try_from(value).map_err(|_| policy(format!("{field} length is too large")))
    }

    fn unsigned(&mut self, field: &str) -> Result<u64, HostError> {
        self.argument(0, field)
    }

    fn text(&mut self, field: &str) -> Result<&'a [u8], HostError> {
        let length = usize::try_from(self.argument(3, field)?)
            .map_err(|_| policy(format!("{field} length is too large")))?;
        self.take(length, field)
    }

    fn argument(&mut self, expected_major: u8, field: &str) -> Result<u64, HostError> {
        let initial = self.byte(field)?;
        if initial >> 5 != expected_major {
            return Err(policy(format!("{field} has the wrong CBOR type")));
        }
        let additional = initial & 0x1f;
        let value = match additional {
            0..=23 => u64::from(additional),
            24 => u64::from(self.byte(field)?),
            25 => self.fixed(2, field)?,
            26 => self.fixed(4, field)?,
            27 => self.fixed(8, field)?,
            _ => return Err(policy(format!("{field} uses indefinite or reserved CBOR"))),
        };
        let minimum = match additional {
            24 => 24,
            25 => 0x100,
            26 => 0x1_0000,
            27 => 0x1_0000_0000,
            _ => 0,
        };
        if additional >= 24 && value < minimum {
            return Err(policy(format!("{field} integer or length is not shortest-form")));
        }
        Ok(value)
    }

    fn fixed(&mut self, length: usize, field: &str) -> Result<u64, HostError> {
        let bytes = self.take(length, field)?;
        let mut value = 0_u64;
        for byte in bytes {
            value = (value << 8) | u64::from(*byte);
        }
        Ok(value)
    }

    fn byte(&mut self, field: &str) -> Result<u8, HostError> {
        let value = self
            .bytes
            .get(self.offset)
            .copied()
            .ok_or_else(|| policy(format!("{field} is truncated")))?;
        self.offset += 1;
        Ok(value)
    }

    fn take(&mut self, length: usize, field: &str) -> Result<&'a [u8], HostError> {
        let end = self
            .offset
            .checked_add(length)
            .filter(|end| *end <= self.bytes.len())
            .ok_or_else(|| policy(format!("{field} is truncated")))?;
        let value = &self.bytes[self.offset..end];
        self.offset = end;
        Ok(value)
    }
}

#[cfg(test)]
mod tests {
    use std::path::PathBuf;

    use super::*;

    fn fixture() -> Vec<u8> {
        let text = include_str!("../protocol/fixtures/sandbox-policy-v1.hex").trim();
        (0..text.len())
            .step_by(2)
            .map(|index| u8::from_str_radix(&text[index..index + 2], 16).unwrap())
            .collect()
    }

    #[test]
    fn decodes_shared_java_fixture() {
        let policy = decode(&fixture()).unwrap();

        assert_eq!(policy.version, 1);
        assert_eq!(policy.handled_rights, HANDLED_FS_RIGHTS);
        assert_eq!(policy.rules[0].path, PathBuf::from("/bin"));
        assert_eq!(policy.rules[2].rights, 20_926);
    }

    #[test]
    fn rejects_non_canonical_and_malformed_policies() {
        let invalid = [
            vec![0x9f, 1, 0xff],
            vec![0x83, 0x18, 1, 0x1a, 0, 1, 0xff, 0xff, 0x80],
            vec![0x83, 2, 0x1a, 0, 1, 0xff, 0xff, 0x80],
            vec![0x83, 1, 0x1a, 0, 0, 0, 1, 0x80],
        ];
        for bytes in invalid {
            assert!(decode(&bytes).is_err(), "accepted {bytes:x?}");
        }
    }

    #[test]
    fn rejects_bad_rules_and_trailing_data() {
        let cases = [
            policy_with_rule("relative", 1),
            policy_with_rule("/a/./b", 1),
            policy_with_rule("/a//b", 1),
            policy_with_rule("/a\0b", 1),
            policy_with_rule("/zero", 0),
            policy_with_rule("/unknown", 1 << 17),
            {
                let mut bytes = fixture();
                bytes.push(0);
                bytes
            },
        ];
        for bytes in cases {
            assert!(decode(&bytes).is_err(), "accepted {bytes:x?}");
        }
    }

    fn policy_with_rule(path: &str, rights: u64) -> Vec<u8> {
        let mut bytes = vec![0x83, 1, 0x1a, 0, 1, 0xff, 0xff, 0x81, 0x82];
        bytes.push(0x60 | u8::try_from(path.len()).unwrap());
        bytes.extend(path.as_bytes());
        if rights < 24 {
            bytes.push(u8::try_from(rights).unwrap());
        } else {
            bytes.extend([0x1a, (rights >> 24) as u8, (rights >> 16) as u8, (rights >> 8) as u8, rights as u8]);
        }
        bytes
    }
}
