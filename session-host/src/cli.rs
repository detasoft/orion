use std::ffi::{OsStr, OsString};
use std::fmt::{Display, Formatter};
use std::path::PathBuf;

pub const DEFAULT_COLS: u16 = 160;
pub const DEFAULT_ROWS: u16 = 50;
pub const DEFAULT_TERM: &str = "xterm-256color";

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
    let mut index = 0;

    while index < arguments.len() {
        let argument = &arguments[index];
        if argument == "--" {
            let command = arguments[index + 1..].to_vec();
            if command.is_empty() {
                return Err(ParseError::new("missing child command after --"));
            }
            return Ok(Command::Run(SessionOptions {
                session_id: required(session_id, "--session-id")?,
                session_dir: required(session_dir, "--session-dir")?,
                cwd: required(cwd, "--cwd")?,
                cols: cols.unwrap_or(DEFAULT_COLS),
                rows: rows.unwrap_or(DEFAULT_ROWS),
                term: term.unwrap_or_else(|| DEFAULT_TERM.to_owned()),
                colorterm,
                sandbox_policy,
                sandbox_unavailable: sandbox_unavailable.unwrap_or(SandboxUnavailable::Fail),
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
}
