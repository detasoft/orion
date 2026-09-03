use std::fmt::{Display, Formatter};
use std::fs::{self, File, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::Arc;

use serde::{Deserialize, Serialize};

pub const STATE_FILE_NAME: &str = "control-retention-state";
const TEMPORARY_STATE_FILE_NAME: &str = "control-retention-state.tmp";
const STATE_VERSION: u16 = 1;

#[derive(Debug)]
pub enum JournalAcknowledgementError {
    Io(io::Error),
    Format(String),
    InvalidWatermark(String),
}

impl Display for JournalAcknowledgementError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Io(error) => write!(formatter, "journal acknowledgement I/O error: {error}"),
            Self::Format(message) => {
                write!(formatter, "invalid journal acknowledgement state: {message}")
            }
            Self::InvalidWatermark(message) => {
                write!(formatter, "invalid journal acknowledgement watermark: {message}")
            }
        }
    }
}

impl std::error::Error for JournalAcknowledgementError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Io(error) => Some(error),
            _ => None,
        }
    }
}

impl From<io::Error> for JournalAcknowledgementError {
    fn from(error: io::Error) -> Self {
        Self::Io(error)
    }
}

#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct PersistedAcknowledgement {
    state_version: u16,
    acknowledged_event_id: u64,
}

trait AcknowledgementFileSystem: Send + Sync {
    fn write_temporary(&self, path: &Path, bytes: &[u8]) -> io::Result<()>;

    fn sync_temporary(&self, path: &Path) -> io::Result<()>;

    fn rename(&self, source: &Path, target: &Path) -> io::Result<()>;

    fn sync_directory(&self, directory: &Path) -> io::Result<()>;
}

struct RealAcknowledgementFileSystem;

impl AcknowledgementFileSystem for RealAcknowledgementFileSystem {
    fn write_temporary(&self, path: &Path, bytes: &[u8]) -> io::Result<()> {
        let mut file = OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .open(path)?;
        file.write_all(bytes)
    }

    fn sync_temporary(&self, path: &Path) -> io::Result<()> {
        OpenOptions::new().write(true).open(path)?.sync_data()
    }

    fn rename(&self, source: &Path, target: &Path) -> io::Result<()> {
        fs::rename(source, target)
    }

    fn sync_directory(&self, directory: &Path) -> io::Result<()> {
        File::open(directory)?.sync_all()
    }
}

pub struct JournalAcknowledgement {
    directory: PathBuf,
    acknowledged_event_id: Option<u64>,
    pending_publication: Option<u64>,
    file_system: Arc<dyn AcknowledgementFileSystem>,
}

impl JournalAcknowledgement {
    pub fn open(directory: impl AsRef<Path>) -> Result<Self, JournalAcknowledgementError> {
        Self::open_with_file_system(directory, Arc::new(RealAcknowledgementFileSystem))
    }

    fn open_with_file_system(
        directory: impl AsRef<Path>,
        file_system: Arc<dyn AcknowledgementFileSystem>,
    ) -> Result<Self, JournalAcknowledgementError> {
        let directory = directory.as_ref().to_path_buf();
        let acknowledged_event_id = read_published_state(&directory)?;
        Ok(Self {
            directory,
            acknowledged_event_id,
            pending_publication: None,
            file_system,
        })
    }

    pub fn acknowledged_event_id(&self) -> Option<u64> {
        self.acknowledged_event_id
    }

    pub fn advance(&mut self, event_id: u64) -> Result<u64, JournalAcknowledgementError> {
        if event_id == 0 {
            return Err(JournalAcknowledgementError::InvalidWatermark(
                "event ID must be nonzero".to_owned(),
            ));
        }
        self.finish_pending_publication()?;
        if let Some(current) = self.acknowledged_event_id {
            if event_id <= current {
                return Ok(current);
            }
        }

        let state = PersistedAcknowledgement {
            state_version: STATE_VERSION,
            acknowledged_event_id: event_id,
        };
        let bytes = serde_json::to_vec(&state).map_err(|error| {
            JournalAcknowledgementError::Format(format!("cannot encode state: {error}"))
        })?;
        let temporary = self.directory.join(TEMPORARY_STATE_FILE_NAME);
        let published = self.directory.join(STATE_FILE_NAME);
        self.file_system.write_temporary(&temporary, &bytes)?;
        self.file_system.sync_temporary(&temporary)?;
        self.file_system.rename(&temporary, &published)?;
        self.pending_publication = Some(event_id);
        self.finish_pending_publication()?;
        Ok(self.acknowledged_event_id.unwrap())
    }

    fn finish_pending_publication(&mut self) -> Result<(), JournalAcknowledgementError> {
        let Some(event_id) = self.pending_publication else {
            return Ok(());
        };
        self.file_system.sync_directory(&self.directory)?;
        self.acknowledged_event_id = Some(
            self.acknowledged_event_id
                .map_or(event_id, |current| current.max(event_id)),
        );
        self.pending_publication = None;
        Ok(())
    }
}

pub fn validate_received_watermark(
    event_id: u64,
    latest_event_id: Option<u64>,
) -> Result<(), JournalAcknowledgementError> {
    if event_id == 0 {
        return Err(JournalAcknowledgementError::InvalidWatermark(
            "event ID must be nonzero".to_owned(),
        ));
    }
    let Some(latest_event_id) = latest_event_id else {
        return Err(JournalAcknowledgementError::InvalidWatermark(
            "an empty journal cannot be acknowledged".to_owned(),
        ));
    };
    if event_id > latest_event_id {
        return Err(JournalAcknowledgementError::InvalidWatermark(format!(
            "event ID {event_id} exceeds the latest journal event ID {latest_event_id}",
        )));
    }
    Ok(())
}

fn read_published_state(directory: &Path) -> Result<Option<u64>, JournalAcknowledgementError> {
    let contents = match fs::read_to_string(directory.join(STATE_FILE_NAME)) {
        Ok(contents) => contents,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error.into()),
    };
    let state: PersistedAcknowledgement = serde_json::from_str(&contents).map_err(|error| {
        JournalAcknowledgementError::Format(format!("cannot decode state: {error}"))
    })?;
    if state.state_version != STATE_VERSION {
        return Err(JournalAcknowledgementError::Format(format!(
            "unsupported state version {}",
            state.state_version,
        )));
    }
    if state.acknowledged_event_id == 0 {
        return Err(JournalAcknowledgementError::Format(
            "acknowledged event ID must be nonzero".to_owned(),
        ));
    }
    Ok(Some(state.acknowledged_event_id))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs::{self, File, OpenOptions};
    use std::io;
    use std::path::{Path, PathBuf};
    use std::sync::{Arc, Mutex};
    use std::time::{SystemTime, UNIX_EPOCH};

    #[derive(Clone, Copy, Debug, Eq, PartialEq)]
    enum Operation {
        WriteTemporary,
        SyncTemporary,
        Rename,
        SyncDirectory,
    }

    struct RecordingFileSystem {
        fail_at: Mutex<Option<Operation>>,
        operations: Mutex<Vec<Operation>>,
    }

    impl RecordingFileSystem {
        fn new(fail_at: Option<Operation>) -> Self {
            Self {
                fail_at: Mutex::new(fail_at),
                operations: Mutex::new(Vec::new()),
            }
        }

        fn record(&self, operation: Operation) -> io::Result<()> {
            self.operations.lock().unwrap().push(operation);
            if *self.fail_at.lock().unwrap() == Some(operation) {
                return Err(io::Error::new(
                    io::ErrorKind::PermissionDenied,
                    "injected acknowledgement failure",
                ));
            }
            Ok(())
        }
    }

    impl AcknowledgementFileSystem for RecordingFileSystem {
        fn write_temporary(&self, path: &Path, bytes: &[u8]) -> io::Result<()> {
            self.record(Operation::WriteTemporary)?;
            fs::write(path, bytes)
        }

        fn sync_temporary(&self, path: &Path) -> io::Result<()> {
            self.record(Operation::SyncTemporary)?;
            OpenOptions::new().write(true).open(path)?.sync_data()
        }

        fn rename(&self, source: &Path, target: &Path) -> io::Result<()> {
            self.record(Operation::Rename)?;
            fs::rename(source, target)
        }

        fn sync_directory(&self, directory: &Path) -> io::Result<()> {
            self.record(Operation::SyncDirectory)?;
            File::open(directory)?.sync_all()
        }
    }

    fn temporary_directory(name: &str) -> PathBuf {
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let directory = std::env::temp_dir().join(format!(
            "orion-session-host-ack-{name}-{}-{unique}",
            std::process::id(),
        ));
        fs::create_dir(&directory).unwrap();
        directory
    }

    #[test]
    fn absence_creation_and_monotonic_updates_have_one_canonical_state() {
        let directory = temporary_directory("state");
        let mut state = JournalAcknowledgement::open(&directory).unwrap();
        assert_eq!(state.acknowledged_event_id(), None);

        assert_eq!(state.advance(42).unwrap(), 42);
        assert_eq!(
            fs::read_to_string(directory.join(STATE_FILE_NAME)).unwrap(),
            r#"{"stateVersion":1,"acknowledgedEventId":42}"#,
        );
        assert_eq!(state.advance(42).unwrap(), 42);
        assert_eq!(state.advance(7).unwrap(), 42);
        assert_eq!(state.acknowledged_event_id(), Some(42));
        assert_eq!(JournalAcknowledgement::open(&directory).unwrap().acknowledged_event_id(), Some(42));
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn rejects_invalid_published_state_and_ignores_unknown_fields() {
        let invalid = [
            "not-json",
            r#"{"stateVersion":2,"acknowledgedEventId":42}"#,
            r#"{"stateVersion":1,"acknowledgedEventId":0}"#,
            r#"{"stateVersion":1}"#,
        ];
        for (index, contents) in invalid.into_iter().enumerate() {
            let directory = temporary_directory(&format!("invalid-{index}"));
            fs::write(directory.join(STATE_FILE_NAME), contents).unwrap();
            assert!(JournalAcknowledgement::open(&directory).is_err());
            fs::remove_dir_all(directory).unwrap();
        }

        let directory = temporary_directory("future-field");
        fs::write(
            directory.join(STATE_FILE_NAME),
            r#"{"stateVersion":1,"acknowledgedEventId":42,"future":"ignored"}"#,
        )
        .unwrap();
        assert_eq!(JournalAcknowledgement::open(&directory).unwrap().acknowledged_event_id(), Some(42));
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn validates_received_watermarks_against_the_logical_tail_before_writing() {
        assert!(validate_received_watermark(0, Some(10)).is_err());
        assert!(validate_received_watermark(1, None).is_err());
        assert!(validate_received_watermark(11, Some(10)).is_err());
        assert!(validate_received_watermark(10, Some(10)).is_ok());
        assert!(validate_received_watermark(u64::MAX, Some(u64::MAX)).is_ok());
    }

    #[test]
    fn failure_before_rename_preserves_the_old_published_watermark() {
        let directory = temporary_directory("before-rename");
        let mut initial = JournalAcknowledgement::open(&directory).unwrap();
        initial.advance(10).unwrap();
        let file_system = Arc::new(RecordingFileSystem::new(Some(Operation::SyncTemporary)));
        let mut state = JournalAcknowledgement::open_with_file_system(&directory, file_system.clone())
            .unwrap();

        assert!(state.advance(20).is_err());
        assert_eq!(state.acknowledged_event_id(), Some(10));
        assert_eq!(JournalAcknowledgement::open(&directory).unwrap().acknowledged_event_id(), Some(10));
        assert_eq!(
            *file_system.operations.lock().unwrap(),
            [Operation::WriteTemporary, Operation::SyncTemporary],
        );
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn failure_after_rename_is_not_reported_as_an_accepted_advancement() {
        let directory = temporary_directory("directory-sync");
        let mut initial = JournalAcknowledgement::open(&directory).unwrap();
        initial.advance(10).unwrap();
        let file_system = Arc::new(RecordingFileSystem::new(Some(Operation::SyncDirectory)));
        let mut state = JournalAcknowledgement::open_with_file_system(&directory, file_system.clone())
            .unwrap();

        assert!(state.advance(20).is_err());
        assert_eq!(state.acknowledged_event_id(), Some(10));
        assert_eq!(
            *file_system.operations.lock().unwrap(),
            [
                Operation::WriteTemporary,
                Operation::SyncTemporary,
                Operation::Rename,
                Operation::SyncDirectory,
            ],
        );
        *file_system.fail_at.lock().unwrap() = None;
        assert_eq!(state.advance(15).unwrap(), 20);
        assert_eq!(state.acknowledged_event_id(), Some(20));
        assert_eq!(
            file_system.operations.lock().unwrap().last(),
            Some(&Operation::SyncDirectory),
        );
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn successful_directory_sync_publishes_state_visible_after_reopen() {
        let directory = temporary_directory("success");
        let file_system = Arc::new(RecordingFileSystem::new(None));
        let mut state = JournalAcknowledgement::open_with_file_system(&directory, file_system.clone())
            .unwrap();

        assert_eq!(state.advance(u64::MAX).unwrap(), u64::MAX);
        assert_eq!(
            JournalAcknowledgement::open(&directory).unwrap().acknowledged_event_id(),
            Some(u64::MAX),
        );
        assert_eq!(
            *file_system.operations.lock().unwrap(),
            [
                Operation::WriteTemporary,
                Operation::SyncTemporary,
                Operation::Rename,
                Operation::SyncDirectory,
            ],
        );
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn abandoned_temporary_state_never_overrides_the_published_checkpoint() {
        let directory = temporary_directory("abandoned");
        let mut state = JournalAcknowledgement::open(&directory).unwrap();
        state.advance(10).unwrap();
        fs::write(
            directory.join(TEMPORARY_STATE_FILE_NAME),
            r#"{"stateVersion":1,"acknowledgedEventId":99}"#,
        )
        .unwrap();

        let mut reopened = JournalAcknowledgement::open(&directory).unwrap();
        assert_eq!(reopened.acknowledged_event_id(), Some(10));
        assert_eq!(reopened.advance(20).unwrap(), 20);
        assert_eq!(JournalAcknowledgement::open(&directory).unwrap().acknowledged_event_id(), Some(20));
        fs::remove_dir_all(directory).unwrap();
    }
}
