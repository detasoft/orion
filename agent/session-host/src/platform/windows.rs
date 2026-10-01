use super::PlatformKind;
use crate::cli::SessionOptions;
use crate::host::HostError;

pub(super) fn current_platform() -> PlatformKind {
    PlatformKind::Windows
}

pub(super) fn run_session(_options: SessionOptions) -> Result<(), HostError> {
    Err(HostError::Unsupported(
        "Windows ConPTY hosting is not implemented yet".to_owned(),
    ))
}
