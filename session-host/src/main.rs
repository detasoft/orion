use std::process::ExitCode;

use orion_session_host::cli::{self, Command};
use orion_session_host::platform::{self, PlatformKind};
use orion_session_host::protocol::{CONTROL_VERSION, JOURNAL_VERSION};

const USAGE: &str = "\
Usage:
  session-host --session-id ID --session-dir PATH --cwd PATH [OPTIONS] -- COMMAND [ARG...]

Required:
  --session-id ID              Stable session identifier
  --session-dir PATH           Session metadata, journal, and control directory
  --cwd PATH                   Child working directory

Options:
  --cols NUMBER                Initial terminal columns (default: 160)
  --rows NUMBER                Initial terminal rows (default: 50)
  --term VALUE                 TERM value (default: xterm-256color)
  --colorterm VALUE            Optional COLORTERM value
  --sandbox-policy PATH        Filesystem sandbox policy
  --sandbox-unavailable MODE   fail or run-unsandboxed (default: fail)
  -h, --help                   Print help
  -V, --version                Print version
";

fn main() -> ExitCode {
    match cli::parse(std::env::args_os()) {
        Ok(Command::Help) => {
            print!("{USAGE}");
            ExitCode::SUCCESS
        }
        Ok(Command::Version) => {
            let platform = match platform::current() {
                PlatformKind::Unix => "unix",
                PlatformKind::Windows => "windows",
            };
            println!(
                "session-host {} journal-v{} control-v{} {platform}",
                env!("CARGO_PKG_VERSION"),
                JOURNAL_VERSION,
                CONTROL_VERSION
            );
            ExitCode::SUCCESS
        }
        Ok(Command::Run(options)) => match platform::run(options) {
            Ok(()) => ExitCode::SUCCESS,
            Err(error) => {
                eprintln!("session-host: {error}");
                ExitCode::from(70)
            }
        },
        Err(error) => {
            eprintln!("session-host: {error}\n\n{USAGE}");
            ExitCode::from(64)
        }
    }
}
