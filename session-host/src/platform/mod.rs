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

#[cfg(unix)]
use unix::current_platform;
#[cfg(windows)]
use windows::current_platform;
