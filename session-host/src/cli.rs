use std::ffi::{OsStr, OsString};
use std::fmt::{Display, Formatter};
use std::path::PathBuf;

pub use crate::journal::{DEFAULT_JOURNAL_MAX_BYTES, DEFAULT_JOURNAL_SEGMENT_BYTES};

pub const DEFAULT_COLS: u16 = 160;
pub const DEFAULT_ROWS: u16 = 50;
pub const DEFAULT_TERM: &str = "xterm-256color";
pub const DEFAULT_MAX_UNACKNOWLEDGED_OPERATIONS: usize = 4096;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum SandboxUnavailable {
    Fail,
    RunUnsandboxed,
}

#[derive(Debug, Eq, PartialEq)]
pub struct SessionOptions {
    pub session_id: String,
    pub session_dir: PathBuf,
    pub cwd: PathBuf,
    pub cols: u16,
    pub rows: u16,
    pub term: String,
    pub colorterm: Option<String>,
    pub sandbox_policy: Option<PathBuf>,
    pub sandbox_unavailable: SandboxUnavailable,
    pub journal_segment_bytes: u64,
    pub journal_max_bytes: u64,
    pub max_unacknowledged_operations: usize,
    pub command: Vec<OsString>,
}

#[derive(Debug, Eq, PartialEq)]
pub enum Command {
    Help,
    Version,
    Run(SessionOptions),
}

#[derive(Debug, Eq, PartialEq)]
pub struct ParseError {
    message: String,
}

impl ParseError {
    fn new(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
        }
    }
}

impl Display for ParseError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(&self.message)
    }
}

impl std::error::Error for ParseError {}

pub fn parse<I>(arguments: I) -> Result<Command, ParseError>
where
    I: IntoIterator<Item = OsString>,
{
    let mut arguments = arguments.into_iter();
    let _program = arguments.next();
    let remaining: Vec<OsString> = arguments.collect();

    if remaining.as_slice() == [OsStr::new("--help")] || remaining.as_slice() == [OsStr::new("-h")] {
        return Ok(Command::Help);
    }
    if remaining.as_slice() == [OsStr::new("--version")] || remaining.as_slice() == [OsStr::new("-V")] {
        return Ok(Command::Version);
    }

    parse_session(remaining)
}

fn parse_session(arguments: Vec<OsString>) -> Result<Command, ParseError> {
    let mut session_id = None;
    let mut session_dir = None;
    let mut cwd = None;
    let mut cols = None;
    let mut rows = None;
    let mut term = None;
    let mut colorterm = None;
    let mut sandbox_policy = None;
    let mut sandbox_unavailable = None;
    let mut journal_segment_bytes = None;
    let mut journal_max_bytes = None;
    let mut max_unacknowledged_operations = None;
    let mut index = 0;

    while index < arguments.len() {
        let argument = &arguments[index];
        if argument == "--" {
            let command = arguments[index + 1..].to_vec();
            if command.is_empty() {
                return Err(ParseError::new("missing child command after --"));
            }
            let session_id = required(session_id, "--session-id")?;
            let session_dir = required(session_dir, "--session-dir")?;
            let cwd = required(cwd, "--cwd")?;
            let journal_segment_bytes =
                journal_segment_bytes.unwrap_or(DEFAULT_JOURNAL_SEGMENT_BYTES);
            let journal_max_bytes = journal_max_bytes.unwrap_or(DEFAULT_JOURNAL_MAX_BYTES);
            let max_unacknowledged_operations = max_unacknowledged_operations
                .unwrap_or(DEFAULT_MAX_UNACKNOWLEDGED_OPERATIONS);
            if journal_max_bytes < journal_segment_bytes {
                return Err(ParseError::new(
                    "--journal-max-bytes must be greater than or equal to --journal-segment-bytes",
                ));
            }
            return Ok(Command::Run(SessionOptions {
                session_id,
                session_dir,
                cwd,
                cols: cols.unwrap_or(DEFAULT_COLS),
                rows: rows.unwrap_or(DEFAULT_ROWS),
                term: term.unwrap_or_else(|| DEFAULT_TERM.to_owned()),
                colorterm,
                sandbox_policy,
                sandbox_unavailable: sandbox_unavailable.unwrap_or(SandboxUnavailable::Fail),
                journal_segment_bytes,
                journal_max_bytes,
                max_unacknowledged_operations,
                command,
            }));
        }

        let option = argument
            .to_str()
            .ok_or_else(|| ParseError::new("session-host options must be valid UTF-8"))?;
        index += 1;
        let value = arguments
            .get(index)
            .ok_or_else(|| ParseError::new(format!("missing value for {option}")))?;

        match option {
            "--session-id" => set_once(&mut session_id, parse_session_id(value)?, option)?,
            "--session-dir" => set_once(&mut session_dir, PathBuf::from(value), option)?,
            "--cwd" => set_once(&mut cwd, PathBuf::from(value), option)?,
            "--cols" => set_once(&mut cols, parse_dimension(value, option)?, option)?,
            "--rows" => set_once(&mut rows, parse_dimension(value, option)?, option)?,
            "--term" => set_once(&mut term, parse_environment_value(value, option)?, option)?,
            "--colorterm" => {
                set_once(&mut colorterm, parse_environment_value(value, option)?, option)?
            }
            "--sandbox-policy" => set_once(&mut sandbox_policy, PathBuf::from(value), option)?,
            "--sandbox-unavailable" => {
                set_once(&mut sandbox_unavailable, parse_sandbox_unavailable(value)?, option)?
            }
            "--journal-segment-bytes" => {
                set_once(&mut journal_segment_bytes, parse_journal_bytes(value, option)?, option)?
            }
            "--journal-max-bytes" => {
                set_once(&mut journal_max_bytes, parse_journal_bytes(value, option)?, option)?
            }
            "--max-unacknowledged-operations" => set_once(
                &mut max_unacknowledged_operations,
                parse_positive_usize(value, option)?,
                option,
            )?,
            _ => return Err(ParseError::new(format!("unknown option: {option}"))),
        }
        index += 1;
    }

    Err(ParseError::new("missing -- separator and child command"))
}

fn required<T>(value: Option<T>, option: &str) -> Result<T, ParseError> {
    value.ok_or_else(|| ParseError::new(format!("missing required option {option}")))
}

fn set_once<T>(slot: &mut Option<T>, value: T, option: &str) -> Result<(), ParseError> {
    if slot.is_some() {
        return Err(ParseError::new(format!("duplicate option: {option}")));
    }
    *slot = Some(value);
    Ok(())
}

fn parse_session_id(value: &OsStr) -> Result<String, ParseError> {
    let value = value
        .to_str()
        .ok_or_else(|| ParseError::new("session ID must be valid UTF-8"))?;
    let valid_length = !value.is_empty() && value.len() <= 128;
    let valid_characters = value
        .bytes()
        .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'));
    if !valid_length || !valid_characters || matches!(value, "." | "..") {
        return Err(ParseError::new(
            "session ID must contain 1-128 ASCII letters, digits, dots, underscores, or hyphens",
        ));
    }
    Ok(value.to_owned())
}

fn parse_dimension(value: &OsStr, option: &str) -> Result<u16, ParseError> {
    let value = value
        .to_str()
        .ok_or_else(|| ParseError::new(format!("{option} must be a decimal integer")))?;
    let parsed = value
        .parse::<u16>()
        .map_err(|_| ParseError::new(format!("{option} must be between 1 and 65535")))?;
    if parsed == 0 {
        return Err(ParseError::new(format!("{option} must be between 1 and 65535")));
    }
    Ok(parsed)
}

fn parse_journal_bytes(value: &OsStr, option: &str) -> Result<u64, ParseError> {
    let error = || ParseError::new(format!("{option} must be a positive decimal integer"));
    let value = value
        .to_str()
        .ok_or_else(&error)?;
    let parsed = value.parse::<u64>().map_err(|_| error())?;
    if parsed == 0 {
        return Err(error());
    }
    Ok(parsed)
}

fn parse_positive_usize(value: &OsStr, option: &str) -> Result<usize, ParseError> {
    let error = || ParseError::new(format!("{option} must be a positive decimal integer"));
    let value = value.to_str().ok_or_else(&error)?;
    let parsed = value.parse::<usize>().map_err(|_| error())?;
    if parsed == 0 {
        return Err(error());
    }
    Ok(parsed)
}

fn parse_environment_value(value: &OsStr, option: &str) -> Result<String, ParseError> {
    let value = value
        .to_str()
        .ok_or_else(|| ParseError::new(format!("{option} must be valid UTF-8")))?;
    if value.is_empty() || value.len() > 128 || value.contains('=') {
        return Err(ParseError::new(format!(
            "{option} must contain 1-128 characters and no equals sign"
        )));
    }
    Ok(value.to_owned())
}

fn parse_sandbox_unavailable(value: &OsStr) -> Result<SandboxUnavailable, ParseError> {
    match value.to_str() {
        Some("fail") => Ok(SandboxUnavailable::Fail),
        Some("run-unsandboxed") => Ok(SandboxUnavailable::RunUnsandboxed),
        _ => Err(ParseError::new(
            "--sandbox-unavailable must be fail or run-unsandboxed",
        )),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn strings(values: &[&str]) -> Vec<OsString> {
        values.iter().map(OsString::from).collect()
    }

    fn session_arguments(options: &[&str]) -> Vec<OsString> {
        let mut arguments = strings(&[
            "session-host",
            "--session-id",
            "session-1",
            "--session-dir",
            "/sessions/session-1",
            "--cwd",
            "/work",
        ]);
        arguments.extend(options.iter().map(OsString::from));
        arguments.extend(strings(&["--", "bash"]));
        arguments
    }

    #[test]
    fn parses_complete_session_command() {
        let command = parse(strings(&[
            "session-host",
            "--session-id",
            "019d-session",
            "--session-dir",
            "/sessions/019d-session",
            "--cwd",
            "/work/project",
            "--cols",
            "180",
            "--rows",
            "60",
            "--term",
            "xterm-test",
            "--colorterm",
            "truecolor",
            "--sandbox-policy",
            "/policy.json",
            "--sandbox-unavailable",
            "run-unsandboxed",
            "--journal-segment-bytes",
            "4096",
            "--journal-max-bytes",
            "16384",
            "--max-unacknowledged-operations",
            "8192",
            "--",
            "bash",
            "-l",
        ]))
        .unwrap();

        assert_eq!(
            command,
            Command::Run(SessionOptions {
                session_id: "019d-session".to_owned(),
                session_dir: PathBuf::from("/sessions/019d-session"),
                cwd: PathBuf::from("/work/project"),
                cols: 180,
                rows: 60,
                term: "xterm-test".to_owned(),
                colorterm: Some("truecolor".to_owned()),
                sandbox_policy: Some(PathBuf::from("/policy.json")),
                sandbox_unavailable: SandboxUnavailable::RunUnsandboxed,
                journal_segment_bytes: 4096,
                journal_max_bytes: 16384,
                max_unacknowledged_operations: 8192,
                command: strings(&["bash", "-l"]),
            })
        );
    }

    #[test]
    fn supplies_terminal_and_sandbox_defaults() {
        let command = parse(strings(&[
            "session-host",
            "--session-id",
            "session-1",
            "--session-dir",
            "/sessions/session-1",
            "--cwd",
            "/work",
            "--",
            "codex",
        ]))
        .unwrap();

        let Command::Run(options) = command else {
            panic!("expected run command");
        };
        assert_eq!(options.cols, DEFAULT_COLS);
        assert_eq!(options.rows, DEFAULT_ROWS);
        assert_eq!(options.term, DEFAULT_TERM);
        assert_eq!(options.sandbox_unavailable, SandboxUnavailable::Fail);
        assert_eq!(options.journal_segment_bytes, DEFAULT_JOURNAL_SEGMENT_BYTES);
        assert_eq!(options.journal_max_bytes, DEFAULT_JOURNAL_MAX_BYTES);
        assert_eq!(options.max_unacknowledged_operations, 4096);
    }

    #[test]
    fn rejects_duplicate_and_invalid_options() {
        let duplicate = parse(strings(&[
            "session-host",
            "--session-id",
            "one",
            "--session-id",
            "two",
            "--",
            "bash",
        ]));
        assert_eq!(duplicate.unwrap_err().to_string(), "duplicate option: --session-id");

        let invalid_size = parse(strings(&[
            "session-host",
            "--cols",
            "0",
            "--",
            "bash",
        ]));
        assert_eq!(
            invalid_size.unwrap_err().to_string(),
            "--cols must be between 1 and 65535"
        );
    }

    #[test]
    fn rejects_malformed_journal_limits() {
        for (option, value) in [
            ("--journal-segment-bytes", "not-a-number"),
            ("--journal-max-bytes", "18446744073709551616"),
        ] {
            let result = parse(session_arguments(&[option, value]));

            assert_eq!(
                result.unwrap_err().to_string(),
                format!("{option} must be a positive decimal integer")
            );
        }
    }

    #[test]
    fn rejects_zero_journal_limits() {
        for option in ["--journal-segment-bytes", "--journal-max-bytes"] {
            let result = parse(session_arguments(&[option, "0"]));

            assert_eq!(
                result.unwrap_err().to_string(),
                format!("{option} must be a positive decimal integer")
            );
        }
    }

    #[test]
    fn rejects_duplicate_journal_limits() {
        for option in ["--journal-segment-bytes", "--journal-max-bytes"] {
            let result = parse(session_arguments(&[option, "4096", option, "8192"]));

            assert_eq!(result.unwrap_err().to_string(), format!("duplicate option: {option}"));
        }
    }

    #[test]
    fn rejects_journal_max_smaller_than_segment() {
        let result = parse(session_arguments(&[
            "--journal-segment-bytes",
            "16384",
            "--journal-max-bytes",
            "4096",
        ]));

        assert_eq!(
            result.unwrap_err().to_string(),
            "--journal-max-bytes must be greater than or equal to --journal-segment-bytes"
        );
    }

    #[test]
    fn parses_and_validates_the_unacknowledged_operation_capacity() {
        let Command::Run(options) = parse(session_arguments(&[
            "--max-unacknowledged-operations",
            "8192",
        ]))
        .unwrap()
        else {
            panic!("expected run command");
        };
        assert_eq!(options.max_unacknowledged_operations, 8192);

        for values in [
            vec!["--max-unacknowledged-operations", "0"],
            vec!["--max-unacknowledged-operations", "not-a-number"],
            vec![
                "--max-unacknowledged-operations",
                "1",
                "--max-unacknowledged-operations",
                "2",
            ],
        ] {
            assert!(parse(session_arguments(&values)).is_err());
        }
    }
}
