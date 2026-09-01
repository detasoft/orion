#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PlatformKind {
    Unix,
    Windows,
}

#[cfg(unix)]
mod unix;
#[cfg(windows)]
mod windows;

pub fn current() -> PlatformKind {
    current_platform()
}

pub fn run(options: SessionOptions) -> Result<(), HostError> {
    run_session(options)
}

use crate::cli::SessionOptions;
use crate::host::HostError;
#[cfg(unix)]
use unix::current_platform;
#[cfg(unix)]
use unix::run_session;
#[cfg(windows)]
use windows::current_platform;
#[cfg(windows)]
use windows::run_session;
