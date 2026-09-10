#[cfg(target_os = "macos")]
use std::collections::HashMap;
use std::ffi::{CString, OsStr};
use std::fs::{self, File};
use std::io::{self, Read, Write};
use std::net::Shutdown;
use std::os::fd::{AsRawFd, FromRawFd};
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::FileTypeExt;
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use super::PlatformKind;
use super::sandbox::PreparedSandbox;
use crate::cli::SessionOptions;
use crate::host::{
    self, ERROR_INVALID_REQUEST, ERROR_INVALID_STATE, ERROR_IO, ERROR_UNSUPPORTED_MESSAGE,
    ERROR_UNSUPPORTED_SCHEMA, HostError, OwnedControlFrame,
};
use crate::journal::{
    self, ControlMetadata, ControlTransport, JournalConfig, JournalEvent, JournalWriter, Metadata,
    SandboxEnforcement, SandboxMetadata, SandboxRuleMetadata, SandboxUnavailablePolicy,
};
use crate::journal_acknowledgement::{JournalAcknowledgement, validate_received_watermark};
use crate::protocol::{self, control_message};

#[cfg(target_os = "linux")]
#[path = "linux_process_tree.rs"]
mod linux_process_tree;

const CONTROL_ENDPOINT: &str = "control.sock";
const DESCENDANT_ABSENCE_CONFIRMATIONS: usize = 3;
const DESCENDANT_POLL_INTERVAL: Duration = Duration::from_millis(10);
const READ_BUFFER_LENGTH: usize = 64 * 1024;
const CONTROL_RESPONSE_WRITE_TIMEOUT: Duration = Duration::from_secs(1);
const CHILD_SETUP_SANDBOX: u8 = 1;
const CHILD_SETUP_CWD: u8 = 2;
const CHILD_SETUP_TERM: u8 = 3;
const CHILD_SETUP_COLORTERM: u8 = 4;
const CHILD_SETUP_EXEC: u8 = 5;

static SIGNAL_PIPE_WRITE_FD: AtomicI32 = AtomicI32::new(-1);

extern "C" fn forward_signal(signal: libc::c_int) {
    let fd = SIGNAL_PIPE_WRITE_FD.load(Ordering::Relaxed);
    if fd >= 0 {
        let byte = [signal as u8];
        unsafe {
            libc::write(fd, byte.as_ptr().cast(), byte.len());
        }
    }
}

struct SignalIngress {
    read_fd: libc::c_int,
    write_fd: libc::c_int,
}

impl SignalIngress {
    fn install() -> Result<Self, HostError> {
        let mut pipe = [0; 2];
        if unsafe { libc::pipe(pipe.as_mut_ptr()) } != 0 {
            return Err(io::Error::last_os_error().into());
        }
        if let Err(error) = set_nonblocking(pipe[0])
            .and_then(|()| set_nonblocking(pipe[1]))
            .and_then(|()| set_close_on_exec(pipe[0]))
            .and_then(|()| set_close_on_exec(pipe[1]))
        {
            unsafe {
                libc::close(pipe[0]);
                libc::close(pipe[1]);
            }
            return Err(error.into());
        }
        let handler = forward_signal as *const () as libc::sighandler_t;
        for signal in forwarded_signals() {
            if unsafe { libc::signal(signal, handler) } == libc::SIG_ERR {
                let error = io::Error::last_os_error();
                unsafe {
                    libc::close(pipe[0]);
                    libc::close(pipe[1]);
                }
                return Err(error.into());
            }
        }
        SIGNAL_PIPE_WRITE_FD.store(pipe[1], Ordering::Release);
        Ok(Self {
            read_fd: pipe[0],
            write_fd: pipe[1],
        })
    }

    fn take_signal(&self) -> Option<libc::c_int> {
        let mut byte = [0_u8; 1];
        let result = unsafe { libc::read(self.read_fd, byte.as_mut_ptr().cast(), byte.len()) };
        (result == 1).then_some(libc::c_int::from(byte[0]))
    }
}

fn set_nonblocking(fd: libc::c_int) -> io::Result<()> {
    let flags = unsafe { libc::fcntl(fd, libc::F_GETFL) };
    if flags < 0 || unsafe { libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) } != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

fn set_close_on_exec(fd: libc::c_int) -> io::Result<()> {
    let flags = unsafe { libc::fcntl(fd, libc::F_GETFD) };
    if flags < 0 || unsafe { libc::fcntl(fd, libc::F_SETFD, flags | libc::FD_CLOEXEC) } != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

impl Drop for SignalIngress {
    fn drop(&mut self) {
        SIGNAL_PIPE_WRITE_FD.store(-1, Ordering::Release);
        unsafe {
            for signal in forwarded_signals() {
                libc::signal(signal, libc::SIG_DFL);
            }
            libc::close(self.read_fd);
            libc::close(self.write_fd);
        }
    }
}

fn forwarded_signals() -> impl Iterator<Item = libc::c_int> {
    (1..=31).filter(|&signal| {
        signal != libc::SIGKILL && signal != libc::SIGSTOP && signal != libc::SIGCHLD
    })
}

struct DescendantAbsenceConfirmation {
    consecutive_empty: usize,
}

impl DescendantAbsenceConfirmation {
    fn new() -> Self {
        Self {
            consecutive_empty: 0,
        }
    }

    fn observe(&mut self, live: bool) -> bool {
        if live {
            self.consecutive_empty = 0;
            return false;
        }
        self.consecutive_empty += 1;
        self.consecutive_empty >= DESCENDANT_ABSENCE_CONFIRMATIONS
    }
}

pub(super) fn current_platform() -> PlatformKind {
    PlatformKind::Unix
}

pub(super) fn run_session(options: SessionOptions) -> Result<(), HostError> {
    let prepared = PreparedCommand::validate(&options)?;
    let sandbox = PreparedSandbox::prepare(&options)?;
    unsafe {
        libc::signal(libc::SIGHUP, libc::SIG_IGN);
        libc::signal(libc::SIGTERM, libc::SIG_IGN);
    }
    journal::create_session_directory_durably(&options.session_dir)?;
    let endpoint_path = options.session_dir.join(CONTROL_ENDPOINT);
    remove_stale_endpoint(&endpoint_path)?;
    let listener = UnixListener::bind(&endpoint_path)?;
    let _endpoint_guard = EndpointGuard(endpoint_path);
    listener.set_nonblocking(true)?;

    let journal = JournalWriter::create(
        &options.session_dir,
        JournalConfig {
            segment_max_bytes: options.journal_segment_bytes,
            journal_max_bytes: options.journal_max_bytes,
        },
    )?;
    let mut pending = PendingStartOutcome::new(journal, options.start_command_id.clone());
    let signal_ingress = match SignalIngress::install() {
        Ok(signal_ingress) => signal_ingress,
        Err(error) => return Err(pending.failed(error)),
    };
    let initialized = match initialize_after_journal(&options, &prepared, sandbox, &mut pending.journal) {
        Ok(initialized) => initialized,
        Err(error) => return Err(pending.failed(error)),
    };
    let child_pid = initialized.child_pid;
    let journal = pending.started(initialized.child_pid_u64);

    let state = Arc::new(Mutex::new(SharedState {
        journal,
        metadata: initialized.metadata,
        master: initialized.master,
        pty_closed: false,
        accepted_sequence_high_watermark: None,
        active_server_connection_floor: None,
        operation_order: Arc::new(Mutex::new(())),
        operations: Arc::new(OperationCoordinator::new()),
        acknowledgement: initialized.acknowledgement,
        descendants: Arc::new(Mutex::new(initialized.descendants)),
        child_live: true,
        exit_code: i32::MIN,
        exit_signal: -1,
    }));
    {
        let state = lock_state(&state)?;
        if let Err(error) = state.persist_metadata() {
            eprintln!("session-host: metadata persistence failed: {error}");
        }
    }

    let stop = Arc::new(AtomicBool::new(false));
    let accept_thread = spawn_accept_loop(listener, Arc::clone(&state), Arc::clone(&stop));
    let reader_master = {
        let state = lock_state(&state)?;
        state.master.try_clone()?
    };
    let reader_state = Arc::clone(&state);
    let reader_thread = thread::spawn(move || copy_pty_output(reader_master, reader_state));

    let wait_status = wait_for_process_tree(child_pid, &state, &signal_ingress)?;
    {
        let mut state = lock_state(&state)?;
        state.child_live = false;
    }
    let reader_result = reader_thread
        .join()
        .map_err(|_| HostError::Thread("PTY reader panicked".to_owned()))?;
    reader_result?;

    let operations = {
        let state = lock_state(&state)?;
        Arc::clone(&state.operations)
    };
    operations.close_admission()?;
    operations.wait_for_operations()?;

    let (exit_code, exit_signal) = decode_wait_status(wait_status);
    let finalization = {
        let mut state = lock_state(&state)?;
        state.exit_code = exit_code;
        state.exit_signal = exit_signal;
        state
            .journal
            .finish_durably(exit_code)
            .map(|_| ())
            .map_err(|error| error.to_string())
    };

    stop.store(true, Ordering::Release);
    let accept_result = accept_thread
        .join()
        .map_err(|_| HostError::Thread("control accept loop panicked".to_owned()))
        .and_then(|result| result)
        .map_err(|error| error.to_string());
    let maintenance = lock_state(&state)?
        .journal
        .finish_maintenance()
        .map_err(|error| error.to_string());
    finish_session(finalization, accept_result, maintenance)
}

fn finish_session(
    finalization: Result<(), String>,
    accept_result: Result<(), String>,
    maintenance: Result<(), String>,
) -> Result<(), HostError> {
    let mut failures = Vec::new();
    failures.extend(
        [finalization, accept_result, maintenance]
            .into_iter()
            .filter_map(Result::err),
    );
    if failures.is_empty() {
        Ok(())
    } else {
        Err(HostError::Protocol(failures.join("; ")))
    }
}

struct PendingStartOutcome {
    journal: JournalWriter,
    command_id: String,
}

impl PendingStartOutcome {
    fn new(journal: JournalWriter, command_id: String) -> Self {
        Self {
            journal,
            command_id,
        }
    }

    fn failed(mut self, launch: HostError) -> HostError {
        let (diagnostic, omitted) = protocol::bound_start_diagnostic(&launch.to_string());
        let persistence = self
            .journal
            .append_durable(JournalEvent::SessionStartFailed {
                command_id: self.command_id.clone(),
                diagnostic,
                omitted_byte_count: omitted,
            })
            .map(|_| ())
            .map_err(|error| HostError::Protocol(format!("journal append failed: {error}")));
        match persistence {
            Ok(()) => launch,
            Err(persistence) => HostError::StartOutcome {
                launch: Box::new(launch),
                persistence: Box::new(persistence),
            },
        }
    }

    fn started(mut self, child_pid: u64) -> JournalWriter {
        if let Err(error) = self
            .journal
            .append_durable(JournalEvent::ProcessStarted(child_pid))
        {
            eprintln!("session-host: failed to record PROCESS_STARTED: {error}");
        }
        self.journal
    }
}

struct InitializedSession {
    acknowledgement: JournalAcknowledgement,
    metadata: Metadata,
    child_pid: libc::pid_t,
    child_pid_u64: u64,
    master: File,
    descendants: DescendantTracker,
}

fn initialize_after_journal(
    options: &SessionOptions,
    prepared: &PreparedCommand,
    sandbox: PreparedSandbox,
    journal: &mut JournalWriter,
) -> Result<InitializedSession, HostError> {
    let acknowledgement = JournalAcknowledgement::open(&options.session_dir)
        .map_err(|error| HostError::Protocol(error.to_string()))?;
    let started_at = epoch_millis()?;
    let mut metadata = initial_metadata(options, &sandbox, started_at)?;
    journal::write_metadata(&options.session_dir, &metadata)?;
    let (child_pid, master, descendants) =
        spawn_pty(prepared, options.cols, options.rows, sandbox, journal)?;
    let child_pid_u64 = u64::try_from(child_pid)
        .map_err(|_| HostError::InvalidOptions("child PID is not representable".to_owned()))?;
    metadata.child_pid = Some(child_pid_u64);
    Ok(InitializedSession {
        acknowledgement,
        metadata,
        child_pid,
        child_pid_u64,
        master,
        descendants,
    })
}

struct EndpointGuard(PathBuf);

impl Drop for EndpointGuard {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.0);
    }
}

struct PreparedCommand {
    cwd: CString,
    arguments: Vec<CString>,
    term: CString,
    colorterm: Option<CString>,
}

impl PreparedCommand {
    fn validate(options: &SessionOptions) -> Result<Self, HostError> {
        let cwd = native_c_string(options.cwd.as_os_str(), "working directory")?;
        let mut arguments = Vec::with_capacity(options.command.len());
        for (index, argument) in options.command.iter().enumerate() {
            arguments.push(native_c_string(
                argument,
                &format!("command argument {index}"),
            )?);
        }
        if arguments.is_empty() {
            return Err(HostError::InvalidOptions(
                "child command is empty".to_owned(),
            ));
        }
        let term = CString::new(options.term.as_bytes())
            .map_err(|_| HostError::InvalidOptions("TERM contains a NUL byte".to_owned()))?;
        let colorterm = options
            .colorterm
            .as_ref()
            .map(|value| CString::new(value.as_bytes()))
            .transpose()
            .map_err(|_| HostError::InvalidOptions("COLORTERM contains a NUL byte".to_owned()))?;
        Ok(Self {
            cwd,
            arguments,
            term,
            colorterm,
        })
    }
}

fn native_c_string(value: &OsStr, description: &str) -> Result<CString, HostError> {
    CString::new(value.as_bytes())
        .map_err(|_| HostError::InvalidOptions(format!("{description} contains a NUL byte")))
}

fn initial_metadata(
    options: &SessionOptions,
    prepared_sandbox: &PreparedSandbox,
    started_at: u64,
) -> Result<Metadata, HostError> {
    let command = options
        .command
        .iter()
        .enumerate()
        .map(|(index, value)| {
            value.to_str().map(str::to_owned).ok_or_else(|| {
                HostError::InvalidOptions(format!("command argument {index} is not valid UTF-8"))
            })
        })
        .collect::<Result<Vec<_>, _>>()?;
    let cwd = options
        .cwd
        .to_str()
        .ok_or_else(|| {
            HostError::InvalidOptions("working directory is not valid UTF-8".to_owned())
        })?
        .to_owned();
    let policy = prepared_sandbox.policy();
    let rules = policy
        .map(|policy| {
            policy
                .rules
                .iter()
                .map(|rule| SandboxRuleMetadata {
                    path: rule.path.to_string_lossy().into_owned(),
                    rights: right_names(rule.rights),
                })
                .collect()
        })
        .unwrap_or_default();
    let read_write_paths = policy_paths(policy, 16_390);
    let read_only_paths = policy_paths(policy, 4);
    Ok(Metadata {
        metadata_version: 1,
        journal_format_version: protocol::JOURNAL_VERSION,
        control_protocol_version: protocol::CONTROL_VERSION,
        session_id: options.session_id.clone(),
        created_at_epoch_millis: started_at,
        session_start_epoch_millis: started_at,
        command,
        cwd,
        host_pid: u64::from(std::process::id()),
        child_pid: None,
        initial_cols: options.cols,
        initial_rows: options.rows,
        current_cols: options.cols,
        current_rows: options.rows,
        term: options.term.clone(),
        sandbox: SandboxMetadata {
            requested: prepared_sandbox.requested(),
            enforcement: if prepared_sandbox.enforced() {
                SandboxEnforcement::Landlock
            } else {
                SandboxEnforcement::None
            },
            unavailable_policy: SandboxUnavailablePolicy::RunUnsandboxed,
            read_write_paths,
            read_only_paths,
            policy_version: policy.map(|policy| policy.version),
            handled_rights: policy.map(|policy| policy.handled_rights),
            rules,
        },
        control: ControlMetadata {
            transport: ControlTransport::UnixDomainSocket,
            endpoint: CONTROL_ENDPOINT.to_owned(),
        },
    })
}

fn policy_paths(policy: Option<&crate::sandbox::CompiledPolicy>, rights: u64) -> Vec<String> {
    policy
        .map(|policy| {
            policy
                .rules
                .iter()
                .filter(|rule| rule.rights == rights)
                .map(|rule| rule.path.to_string_lossy().into_owned())
                .collect()
        })
        .unwrap_or_default()
}

fn right_names(mask: u64) -> Vec<String> {
    const NAMES: [&str; 17] = [
        "execute",
        "write-file",
        "read-file",
        "read-dir",
        "remove-dir",
        "remove-file",
        "make-char",
        "make-dir",
        "make-reg",
        "make-sock",
        "make-fifo",
        "make-block",
        "make-sym",
        "refer",
        "truncate",
        "ioctl-dev",
        "resolve-unix",
    ];
    NAMES
        .iter()
        .enumerate()
        .filter(|(bit, _)| mask & (1 << bit) != 0)
        .map(|(_, name)| (*name).to_owned())
        .collect()
}

fn spawn_pty(
    command: &PreparedCommand,
    cols: u16,
    rows: u16,
    sandbox: PreparedSandbox,
    journal: &mut JournalWriter,
) -> Result<(libc::pid_t, File, DescendantTracker), HostError> {
    let held = fork_pty_held(command, cols, rows, sandbox)?;
    set_nonblocking(held.master_fd())?;
    #[cfg(target_os = "macos")]
    let mut held = held;
    #[cfg(target_os = "linux")]
    let (held, descendants) = linux_process_tree::initialize_held_child(
        held,
        |reason| {
            journal
                .append_durable(JournalEvent::HostWarning {
                    code: protocol::host_warning::CGROUP_FALLBACK,
                    message: reason.to_owned(),
                })
                .map(|_| ())
                .map_err(HostError::from)
        },
        |held, _backend| {
            let pty = pty_slave_identity(held.master_fd())?;
            Ok((held.pid(), linux_process_tree::process_start(held.pid(), pty)?, pty))
        },
        |(root, root_start, pty), backend| DescendantTracker::activate(backend, root, root_start, pty),
    )?;
    #[cfg(target_os = "macos")]
    let (held, descendants) = {
        let _ = journal;
        let pty = pty_slave_identity(held.master_fd())?;
        let descendants = DescendantTracker::new(held.pid(), pty)?;
        held.release()?;
        (held, descendants)
    };
    let (pid, master) = held.into_parts();
    Ok((pid, master, descendants))
}

struct HeldChild {
    pid: libc::pid_t,
    master: Option<File>,
    release: Option<File>,
    setup: Option<File>,
    aborted: bool,
    released: bool,
}

impl HeldChild {
    fn pid(&self) -> libc::pid_t {
        self.pid
    }

    fn master_fd(&self) -> libc::c_int {
        self.master.as_ref().expect("held child master is present").as_raw_fd()
    }

    fn release(&mut self) -> Result<(), HostError> {
        let mut release = self
            .release
            .take()
            .ok_or_else(|| io::Error::other("held child release pipe is unavailable"))?;
        release.write_all(&[1])?;
        drop(release);

        let mut setup = self
            .setup
            .take()
            .ok_or_else(|| io::Error::other("held child setup pipe is unavailable"))?;
        let mut code = [0_u8; 1];
        let result = loop {
            match setup.read(&mut code) {
                Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
                result => break result,
            }
        }?;
        if result == 1 {
            return Err(child_setup_error(code[0]));
        }
        self.released = true;
        Ok(())
    }

    fn abort(&mut self) -> Result<(), HostError> {
        if self.released {
            return Err(io::Error::other("released child cannot be aborted").into());
        }
        if self.aborted {
            return Ok(());
        }
        drop(self.release.take());
        drop(self.setup.take());
        let kill_error = if unsafe { libc::kill(self.pid, libc::SIGKILL) } == 0 {
            None
        } else {
            let error = io::Error::last_os_error();
            (error.raw_os_error() != Some(libc::ESRCH)).then_some(error)
        };
        let reap_error = match wait_for_child(self.pid) {
            Ok(_) => {
                self.aborted = true;
                None
            }
            Err(error) => Some(error),
        };
        match (kill_error, reap_error) {
            (None, None) => Ok(()),
            (Some(error), None) => Err(error.into()),
            (None, Some(error)) => Err(error),
            (Some(kill), Some(reap)) => Err(io::Error::other(format!(
                "failed to kill held child: {kill}; failed to reap held child: {reap}"
            ))
            .into()),
        }
    }

    fn into_parts(mut self) -> (libc::pid_t, File) {
        assert!(self.released, "held child must be released before use");
        let master = self.master.take().expect("held child master is present");
        (self.pid, master)
    }
}

impl Drop for HeldChild {
    fn drop(&mut self) {
        if self.released || self.aborted {
            return;
        }
        let _ = self.abort();
    }
}

fn fork_pty_held(
    command: &PreparedCommand,
    cols: u16,
    rows: u16,
    sandbox: PreparedSandbox,
) -> Result<HeldChild, HostError> {
    prepare_descendant_tracking()?;
    let mut start_pipe = [-1; 2];
    if unsafe { libc::pipe(start_pipe.as_mut_ptr()) } != 0 {
        return Err(io::Error::last_os_error().into());
    }
    let mut setup_pipe = [-1; 2];
    if unsafe { libc::pipe(setup_pipe.as_mut_ptr()) } != 0 {
        unsafe {
            libc::close(start_pipe[0]);
            libc::close(start_pipe[1]);
        }
        return Err(io::Error::last_os_error().into());
    }
    if unsafe { libc::fcntl(setup_pipe[1], libc::F_SETFD, libc::FD_CLOEXEC) } != 0 {
        let error = io::Error::last_os_error();
        unsafe {
            libc::close(start_pipe[0]);
            libc::close(start_pipe[1]);
            libc::close(setup_pipe[0]);
            libc::close(setup_pipe[1]);
        }
        return Err(error.into());
    }
    let mut master = -1;
    let mut dimensions = libc::winsize {
        ws_row: rows,
        ws_col: cols,
        ws_xpixel: 0,
        ws_ypixel: 0,
    };
    let mut argument_pointers: Vec<*const libc::c_char> = command
        .arguments
        .iter()
        .map(|value| value.as_ptr())
        .collect();
    argument_pointers.push(std::ptr::null());
    let pid = unsafe {
        libc::forkpty(
            &mut master,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            &mut dimensions,
        )
    };
    if pid < 0 {
        unsafe {
            libc::close(start_pipe[0]);
            libc::close(start_pipe[1]);
            libc::close(setup_pipe[0]);
            libc::close(setup_pipe[1]);
        }
        return Err(io::Error::last_os_error().into());
    }
    if pid == 0 {
        unsafe {
            libc::close(setup_pipe[0]);
            libc::close(start_pipe[1]);
            let mut release = 0_u8;
            loop {
                let result = libc::read(start_pipe[0], (&mut release as *mut u8).cast(), 1);
                if result == 1 {
                    break;
                }
                if result == 0
                    || (result < 0
                        && io::Error::last_os_error().kind() != io::ErrorKind::Interrupted)
                {
                    libc::_exit(127);
                }
            }
            libc::close(start_pipe[0]);
        }
        if sandbox.restrict_child().is_err() {
            unsafe { child_exec_failed(setup_pipe[1], CHILD_SETUP_SANDBOX, b"") }
        }
        exec_child(command, &argument_pointers, setup_pipe[1]);
    }
    unsafe {
        libc::close(start_pipe[0]);
        libc::close(setup_pipe[1]);
    }
    Ok(HeldChild {
        pid,
        master: Some(unsafe { File::from_raw_fd(master) }),
        release: Some(unsafe { File::from_raw_fd(start_pipe[1]) }),
        setup: Some(unsafe { File::from_raw_fd(setup_pipe[0]) }),
        aborted: false,
        released: false,
    })
}

#[derive(Clone, Copy)]
struct PtySlaveIdentity {
    device: libc::dev_t,
    inode: libc::ino_t,
}

fn pty_slave_identity(master: libc::c_int) -> io::Result<PtySlaveIdentity> {
    let path = unsafe { libc::ptsname(master) };
    if path.is_null() {
        return Err(io::Error::last_os_error());
    }
    let mut metadata: libc::stat = unsafe { std::mem::zeroed() };
    if unsafe { libc::stat(path, &mut metadata) } != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(PtySlaveIdentity {
        device: metadata.st_rdev,
        inode: metadata.st_ino,
    })
}

fn exec_child(
    command: &PreparedCommand,
    arguments: &[*const libc::c_char],
    setup_fd: libc::c_int,
) -> ! {
    unsafe {
        libc::signal(libc::SIGHUP, libc::SIG_DFL);
        libc::signal(libc::SIGPIPE, libc::SIG_DFL);
        libc::signal(libc::SIGTERM, libc::SIG_DFL);
        if libc::chdir(command.cwd.as_ptr()) != 0 {
            child_exec_failed(
                setup_fd,
                CHILD_SETUP_CWD,
                b"session-host: cannot change child working directory\r\n",
            );
        }
        if libc::setenv(c"TERM".as_ptr(), command.term.as_ptr(), 1) != 0 {
            child_exec_failed(
                setup_fd,
                CHILD_SETUP_TERM,
                b"session-host: cannot set TERM\r\n",
            );
        }
        if let Some(colorterm) = &command.colorterm
            && libc::setenv(c"COLORTERM".as_ptr(), colorterm.as_ptr(), 1) != 0
        {
            child_exec_failed(
                setup_fd,
                CHILD_SETUP_COLORTERM,
                b"session-host: cannot set COLORTERM\r\n",
            );
        }
        libc::execvp(arguments[0], arguments.as_ptr());
        child_exec_failed(
            setup_fd,
            CHILD_SETUP_EXEC,
            b"session-host: cannot execute child command\r\n",
        );
    }
}

unsafe fn child_exec_failed(setup_fd: libc::c_int, code: u8, message: &[u8]) -> ! {
    unsafe {
        while libc::write(setup_fd, (&code as *const u8).cast(), 1) < 0
            && io::Error::last_os_error().kind() == io::ErrorKind::Interrupted
        {}
        libc::write(libc::STDERR_FILENO, message.as_ptr().cast(), message.len());
        libc::_exit(127);
    }
}

fn child_setup_error(code: u8) -> HostError {
    let detail = match code {
        CHILD_SETUP_SANDBOX => {
            return HostError::Policy("child failed to apply Landlock policy".to_owned());
        }
        CHILD_SETUP_CWD => "child failed to change working directory",
        CHILD_SETUP_TERM => "child failed to set TERM",
        CHILD_SETUP_COLORTERM => "child failed to set COLORTERM",
        CHILD_SETUP_EXEC => "child command exec failed",
        _ => "child reported an unknown pre-exec failure",
    };
    HostError::Io(io::Error::other(detail))
}

struct SharedState {
    journal: JournalWriter,
    metadata: Metadata,
    master: File,
    pty_closed: bool,
    accepted_sequence_high_watermark: Option<u64>,
    active_server_connection_floor: Option<u64>,
    operation_order: Arc<Mutex<()>>,
    operations: Arc<OperationCoordinator>,
    acknowledgement: JournalAcknowledgement,
    descendants: Arc<Mutex<DescendantTracker>>,
    child_live: bool,
    exit_code: i32,
    exit_signal: i32,
}

struct OperationCoordinator {
    state: Mutex<OperationState>,
    changed: std::sync::Condvar,
}

struct OperationState {
    admission_open: bool,
    active_operations: usize,
}

impl OperationCoordinator {
    fn new() -> Self {
        Self {
            state: Mutex::new(OperationState {
                admission_open: true,
                active_operations: 0,
            }),
            changed: std::sync::Condvar::new(),
        }
    }

    fn lock_state(&self) -> Result<std::sync::MutexGuard<'_, OperationState>, HostError> {
        self.state
            .lock()
            .map_err(|_| HostError::Thread("operation coordinator mutex is poisoned".to_owned()))
    }

    fn register_operation(self: &Arc<Self>) -> Result<Option<ActiveOperation>, HostError> {
        let mut state = self.lock_state()?;
        if !state.admission_open {
            return Ok(None);
        }
        state.active_operations += 1;
        Ok(Some(ActiveOperation {
            operations: Arc::clone(self),
        }))
    }

    fn close_admission(&self) -> Result<(), HostError> {
        let mut state = self.lock_state()?;
        state.admission_open = false;
        self.changed.notify_all();
        Ok(())
    }

    fn operation_done(&self) -> Result<(), HostError> {
        let mut state = self.lock_state()?;
        state.active_operations = state.active_operations.saturating_sub(1);
        self.changed.notify_all();
        Ok(())
    }

    fn wait_for_operations(&self) -> Result<(), HostError> {
        let mut state = self.lock_state()?;
        while state.active_operations != 0 {
            state = self.changed.wait(state).map_err(|_| {
                HostError::Thread("operation coordinator mutex is poisoned".to_owned())
            })?;
        }
        Ok(())
    }
}

struct ActiveOperation {
    operations: Arc<OperationCoordinator>,
}

impl Drop for ActiveOperation {
    fn drop(&mut self) {
        let _ = self.operations.operation_done();
    }
}

impl SharedState {
    fn close_pty(&mut self) {
        if self.pty_closed {
            return;
        }
        self.pty_closed = true;
        if let Err(error) = self.append_durable(JournalEvent::PtyClosed) {
            eprintln!("session-host: PTY_CLOSED was not persisted: {error}");
        }
    }

    fn require_pty(&self) -> Result<(), String> {
        if self.pty_closed {
            Err("PTY is closed".to_owned())
        } else {
            Ok(())
        }
    }

    fn append_buffered(&mut self, event: JournalEvent) -> Result<u64, HostError> {
        Ok(self.journal.append_buffered(event)?)
    }

    fn append_durable(&mut self, event: JournalEvent) -> Result<u64, HostError> {
        Ok(self.journal.append_durable(event)?)
    }

    fn persist_metadata(&self) -> Result<(), HostError> {
        journal::write_metadata(self.journal.directory(), &self.metadata)?;
        Ok(())
    }
}

fn copy_pty_output(mut master: File, state: Arc<Mutex<SharedState>>) -> Result<(), HostError> {
    let mut buffer = vec![0_u8; READ_BUFFER_LENGTH];
    let mut journal_available = true;
    loop {
        match master.read(&mut buffer) {
            Ok(0) => break,
            Ok(length) => {
                let mut state = match lock_state(&state) {
                    Ok(state) => state,
                    Err(error) => {
                        if journal_available {
                            eprintln!(
                                "session-host: PTY output journaling failed; \
                                 continuing to drain PTY: {error}"
                            );
                            journal_available = false;
                        }
                        continue;
                    }
                };
                match state.append_buffered(JournalEvent::PtyOutput(buffer[..length].to_vec())) {
                    Ok(_) => journal_available = true,
                    Err(error) => {
                        if journal_available {
                            eprintln!(
                                "session-host: PTY output journaling failed; \
                                 continuing to drain PTY: {error}"
                            );
                            journal_available = false;
                        }
                    }
                }
            }
            Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
            Err(error) if error.kind() == io::ErrorKind::WouldBlock => {
                let mut descriptor = libc::pollfd {
                    fd: master.as_raw_fd(),
                    events: libc::POLLIN,
                    revents: 0,
                };
                if unsafe { libc::poll(&mut descriptor, 1, -1) } < 0 {
                    let error = io::Error::last_os_error();
                    if error.kind() != io::ErrorKind::Interrupted {
                        return Err(error.into());
                    }
                }
            }
            Err(error) if error.raw_os_error() == Some(libc::EIO) => break,
            Err(error) => return Err(error.into()),
        }
    }
    lock_state(&state)?.close_pty();
    Ok(())
}

fn spawn_accept_loop(
    listener: UnixListener,
    state: Arc<Mutex<SharedState>>,
    stop: Arc<AtomicBool>,
) -> thread::JoinHandle<Result<(), HostError>> {
    thread::spawn(move || {
        let mut next_connection_ordinal = 1_u64;
        while !stop.load(Ordering::Acquire) {
            match listener.accept() {
                Ok((stream, _address)) => {
                    stream.set_nonblocking(false)?;
                    let connection_ordinal =
                        take_connection_ordinal(&mut next_connection_ordinal)?;
                    let state = Arc::clone(&state);
                    thread::spawn(move || {
                        if let Err(error) = serve_connection(stream, connection_ordinal, state) {
                            eprintln!("session-host: control connection failed: {error}");
                        }
                    });
                }
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => {
                    thread::sleep(Duration::from_millis(10));
                }
                Err(error) if error.kind() == io::ErrorKind::Interrupted => {}
                Err(error) => return Err(error.into()),
            }
        }
        Ok(())
    })
}

fn take_connection_ordinal(next: &mut u64) -> Result<u64, HostError> {
    let ordinal = *next;
    *next = ordinal.checked_add(1).ok_or_else(|| {
        HostError::Thread("control connection ordinal space is exhausted".to_owned())
    })?;
    Ok(ordinal)
}

fn serve_connection(
    mut stream: UnixStream,
    connection_ordinal: u64,
    state: Arc<Mutex<SharedState>>,
) -> Result<(), HostError> {
    if let Err(error) = stream.set_write_timeout(Some(CONTROL_RESPONSE_WRITE_TIMEOUT))
        && !matches!(
            error.raw_os_error(),
            Some(libc::EINVAL) | Some(libc::ENOTCONN)
        )
    {
        return Err(error.into());
    }
    while let Some(frame) = host::read_control_frame(&mut stream)? {
        if is_operation_control(frame.message_type)
            && (frame.payload_schema_version == 3
                || (frame.message_type == control_message::SIGNAL && frame.payload_schema_version == 4))
        {
            handle_operation(&mut stream, &frame, connection_ordinal, &state)?;
        } else if let Some((message_type, sequence, payload)) =
            handle_request(&frame, connection_ordinal, &state)
        {
            host::write_control_frame(&mut stream, message_type, sequence, &payload)?;
        }
    }
    Ok(())
}

fn is_operation_control(message_type: u16) -> bool {
    matches!(
        message_type,
        control_message::INPUT
            | control_message::RESIZE
            | control_message::SIGNAL
            | control_message::TERMINATE
            | control_message::ACK_JOURNAL
    )
}

fn handle_request(
    frame: &OwnedControlFrame,
    connection_ordinal: u64,
    state: &Arc<Mutex<SharedState>>,
) -> Option<(u16, u64, Vec<u8>)> {
    match frame.message_type {
        control_message::INPUT
        | control_message::RESIZE
        | control_message::SIGNAL
        | control_message::TERMINATE
        | control_message::ACK_JOURNAL => {
            if frame.payload_schema_version != 3 {
                Some(response_error(
                    frame.sequence,
                    ERROR_UNSUPPORTED_SCHEMA,
                    "operation controls require schema 3",
                ))
            } else {
                unreachable!("operation controls are handled by serve_connection")
            }
        }
        control_message::STATUS => {
            if frame.payload_schema_version != 1 {
                Some(response_error(
                    frame.sequence,
                    ERROR_UNSUPPORTED_SCHEMA,
                    "STATUS requires schema 1",
                ))
            } else {
                match handle_status(&frame.payload, state) {
                    Ok(payload) => {
                        Some((control_message::STATUS_RESPONSE, frame.sequence, payload))
                    }
                    Err((code, detail)) => Some(response_error(frame.sequence, code, &detail)),
                }
            }
        }
        control_message::LIST_PROCESSES => {
            let result = if frame.payload_schema_version != 1 {
                Err((ERROR_UNSUPPORTED_SCHEMA, "LIST_PROCESSES requires schema 1".to_owned()))
            } else if !frame.payload.is_empty() {
                Err((ERROR_INVALID_REQUEST, "LIST_PROCESSES payload must be empty".to_owned()))
            } else {
                list_processes(state).map_err(|detail| (ERROR_IO, detail))
            };
            Some(match result {
                Ok(payload) => (control_message::LIST_PROCESSES_RESPONSE, frame.sequence, payload),
                Err((code, detail)) => response_error(frame.sequence, code, &detail),
            })
        }
        control_message::CLAIM_SERVER_CONTROL => {
            let result = if frame.payload_schema_version != 1 {
                Err((
                    ERROR_UNSUPPORTED_SCHEMA,
                    "CLAIM_SERVER_CONTROL requires schema 1".to_owned(),
                ))
            } else {
                claim_server_control(connection_ordinal, &frame.payload, state)
            };
            Some(match result {
                Ok(payload) => (
                    control_message::SERVER_CONTROL_CLAIMED,
                    frame.sequence,
                    payload.to_vec(),
                ),
                Err((code, detail)) => response_error(frame.sequence, code, &detail),
            })
        }
        control_message::APPEND_EVENT => {
            if frame.payload_schema_version != 1 {
                Some(response_error(
                    frame.sequence,
                    ERROR_UNSUPPORTED_SCHEMA,
                    "APPEND_EVENT requires schema 1",
                ))
            } else {
                Some(response_error(
                    u64::MAX,
                    ERROR_UNSUPPORTED_MESSAGE,
                    "ordered harness event ingress is not enabled yet",
                ))
            }
        }
        _ => Some(response_error(
            u64::MAX,
            ERROR_UNSUPPORTED_MESSAGE,
            "unsupported control message",
        )),
    }
}

fn handle_operation(
    stream: &mut UnixStream,
    frame: &OwnedControlFrame,
    connection_ordinal: u64,
    shared: &Arc<Mutex<SharedState>>,
) -> Result<(), HostError> {
    let operation_sequence = frame.sequence;
    if operation_sequence == 0 || operation_sequence == u64::MAX {
        return send_operation_rejection(
            stream,
            frame,
            "operation sequence must be between 1 and u64::MAX - 1",
        );
    }
    let operation = match protocol::decode_operation_control_payload(
        frame.message_type,
        operation_sequence,
        &frame.payload,
    ) {
        Ok(operation) => operation,
        Err(error) => {
            let detail = error.to_string();
            return send_operation_rejection(stream, frame, &detail);
        }
    };
    if frame.message_type == control_message::SIGNAL {
        let expected_length = if frame.payload_schema_version == 4 { 16 } else { 8 };
        if operation.effect.len() != expected_length {
            return send_operation_rejection(stream, frame, "SIGNAL effect length does not match schema");
        }
        if let Err(detail) = parse_signal(&operation.effect[..8]) {
            return send_received(stream, frame, Some((ERROR_INVALID_REQUEST, detail)));
        }
    }
    let admission = match lock_state(shared) {
        Ok(mut state) => {
            if operation.source == protocol::OperationSource::Server
                && state
                    .active_server_connection_floor
                    .is_some_and(|floor| connection_ordinal < floor)
            {
                Err((
                    ERROR_INVALID_STATE,
                    "server control connection is fenced".to_owned(),
                ))
            } else if operation.source == protocol::OperationSource::Server
                && state
                    .accepted_sequence_high_watermark
                    .is_some_and(|watermark| operation_sequence <= watermark)
            {
                Err((
                    ERROR_INVALID_REQUEST,
                    "operation sequence is stale".to_owned(),
                ))
            } else {
                match state.operations.register_operation() {
                    Ok(Some(active_operation)) => {
                        if operation.source == protocol::OperationSource::Server {
                            state.accepted_sequence_high_watermark = Some(operation_sequence);
                        }
                        Ok((Arc::clone(&state.operation_order), active_operation))
                    }
                    Ok(None) => Err((
                        ERROR_INVALID_STATE,
                        "session finalization has started".to_owned(),
                    )),
                    Err(error) => Err((ERROR_IO, error.to_string())),
                }
            }
        }
        Err(error) => Err((ERROR_IO, error.to_string())),
    };
    let (operation_order, _active_operation) = match admission {
        Ok(admission) => admission,
        Err((code, detail)) => {
            send_received(stream, frame, Some((code, &detail)))?;
            return Ok(());
        }
    };
    send_received(stream, frame, None)?;
    let _operation_guard = if frame.message_type == control_message::TERMINATE {
        None
    } else {
        match operation_order.lock() {
            Ok(guard) => Some(guard),
            Err(_) => {
                eprintln!(
                    "session-host: operation {} was received but operation order mutex is poisoned",
                    operation_sequence
                );
                return Ok(());
            }
        }
    };

    let effect_result = execute_operation_effect(frame.message_type, &operation.effect, shared);
    let (outcome, detail) = match effect_result {
        Ok(()) => (protocol::CommandOutcome::Succeeded, String::new()),
        Err(detail) => (
            protocol::CommandOutcome::Failed,
            bounded_result_detail(&detail),
        ),
    };
    {
        let mut state = match lock_state(shared) {
            Ok(state) => state,
            Err(error) => {
                eprintln!(
                    "session-host: operation {operation_sequence} could not be journaled: {error}"
                );
                return Ok(());
            }
        };
        if let Err(error) = state.append_durable(JournalEvent::CommandResult {
            source: operation.source,
            operation_sequence,
            source_envelope: operation.result_envelope,
            outcome,
            detail,
        }) {
            eprintln!(
                "session-host: COMMAND_RESULT for operation {} was not persisted: {error}",
                operation_sequence
            );
        }
    }
    Ok(())
}

fn claim_server_control(
    connection_ordinal: u64,
    payload: &[u8],
    shared: &Arc<Mutex<SharedState>>,
) -> Result<[u8; 16], (u32, String)> {
    let observed_floor = protocol::decode_server_control_claim_payload(payload)
        .map_err(|error| (ERROR_INVALID_REQUEST, error.to_string()))?;
    let mut state = lock_state(shared).map_err(|error| (ERROR_IO, error.to_string()))?;
    if state
        .active_server_connection_floor
        .is_some_and(|floor| connection_ordinal < floor)
    {
        return Err((
            ERROR_INVALID_STATE,
            "server control connection is fenced".to_owned(),
        ));
    }
    if observed_floor.is_some_and(|floor| {
        state
            .accepted_sequence_high_watermark
            .is_none_or(|accepted| floor > accepted)
    }) {
        return Err((
            ERROR_INVALID_STATE,
            "recorded server sequence exceeds host admission".to_owned(),
        ));
    }
    state.active_server_connection_floor = Some(connection_ordinal);
    protocol::server_control_claimed_payload(
        state.accepted_sequence_high_watermark,
        state.acknowledgement.acknowledged_event_id(),
    )
    .map_err(|error| (ERROR_INVALID_STATE, error.to_string()))
}

fn send_operation_rejection(
    stream: &mut UnixStream,
    frame: &OwnedControlFrame,
    detail: &str,
) -> Result<(), HostError> {
    send_received(stream, frame, Some((ERROR_INVALID_REQUEST, detail)))
}

fn send_received(
    stream: &mut UnixStream,
    frame: &OwnedControlFrame,
    error: Option<(u32, &str)>,
) -> Result<(), HostError> {
    let payload = error
        .map(|(code, detail)| host::error_payload(code, detail))
        .unwrap_or_default();
    if let Err(error) =
        host::write_control_frame(stream, control_message::RECEIVED, frame.sequence, &payload)
    {
        eprintln!(
            "session-host: RECEIVED for sequence {} was not delivered: {error}",
            frame.sequence
        );
        let _ = stream.shutdown(Shutdown::Both);
    }
    Ok(())
}

fn bounded_result_detail(detail: &str) -> String {
    let mut end = detail.len().min(4096);
    while !detail.is_char_boundary(end) {
        end -= 1;
    }
    detail[..end].to_owned()
}

fn execute_operation_effect(
    message_type: u16,
    effect: &[u8],
    state: &Arc<Mutex<SharedState>>,
) -> Result<(), String> {
    match message_type {
        control_message::INPUT => apply_input(effect, state),
        control_message::RESIZE => apply_resize(effect, state),
        control_message::SIGNAL => {
            let (kind, signal) = parse_signal(&effect[..8]).map_err(str::to_owned)?;
            if effect.len() == 16 {
                apply_process_signal(u64::from_le_bytes(effect[8..16].try_into().unwrap()), kind, signal, state)
            } else {
                apply_foreground_signal(kind, signal, state)
            }
        }
        control_message::TERMINATE => apply_terminate(effect, state),
        control_message::ACK_JOURNAL => apply_journal_acknowledgement(effect, state),
        _ => Err("unsupported operation control".to_owned()),
    }
}

fn apply_input(payload: &[u8], shared: &Arc<Mutex<SharedState>>) -> Result<(), String> {
    let mut state = lock_state(shared).map_err(|error| error.to_string())?;
    state.require_pty()?;
    let command_id = payload
        .get(0..16)
        .and_then(|raw| <&[u8; 16]>::try_from(raw).ok())
        .ok_or_else(|| "PTY_INPUT payload is shorter than its UUID".to_owned())?;
    state
        .append_buffered(JournalEvent::PtyInput {
            command_id: *command_id,
            payload: payload[16..].to_vec(),
        })
        .map_err(|error| error.to_string())?;
    let master = state
        .master
        .try_clone()
        .map_err(|error| error.to_string())?;
    drop(state);
    let mut written = 0;
    while written < payload.len() - 16 {
        let mut pollfd = libc::pollfd {
            fd: master.as_raw_fd(),
            events: libc::POLLOUT,
            revents: 0,
        };
        let ready = unsafe { libc::poll(&mut pollfd, 1, 100) };
        if ready < 0 {
            let error = io::Error::last_os_error();
            if error.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            return Err(error.to_string());
        }
        let state = lock_state(shared).map_err(|error| error.to_string())?;
        state.require_pty()?;
        if ready == 0 {
            continue;
        }
        if pollfd.revents & (libc::POLLHUP | libc::POLLERR | libc::POLLNVAL) != 0 {
            return Err("PTY input is closed".to_owned());
        }
        let chunk_end = (written + 4096).min(payload.len() - 16);
        let chunk = &payload[16 + written..16 + chunk_end];
        let result = unsafe { libc::write(master.as_raw_fd(), chunk.as_ptr().cast(), chunk.len()) };
        if result < 0 {
            let error = io::Error::last_os_error();
            if matches!(
                error.kind(),
                io::ErrorKind::Interrupted | io::ErrorKind::WouldBlock
            ) {
                continue;
            }
            return Err(error.to_string());
        }
        if result == 0 {
            return Err("PTY input write made no progress".to_owned());
        }
        written += result as usize;
    }
    Ok(())
}

fn apply_resize(payload: &[u8], state: &Arc<Mutex<SharedState>>) -> Result<(), String> {
    let cols = host::u32_at(&payload[0..4]);
    let rows = host::u32_at(&payload[4..8]);
    let mut state = lock_state(state).map_err(|error| error.to_string())?;
    state.require_pty()?;
    state
        .append_buffered(JournalEvent::PtyResize { cols, rows })
        .map_err(|error| error.to_string())?;
    let dimensions = libc::winsize {
        ws_row: rows as u16,
        ws_col: cols as u16,
        ws_xpixel: 0,
        ws_ypixel: 0,
    };
    if unsafe { libc::ioctl(state.master.as_raw_fd(), libc::TIOCSWINSZ, &dimensions) } != 0 {
        return Err(io::Error::last_os_error().to_string());
    }
    state.metadata.current_cols = cols as u16;
    state.metadata.current_rows = rows as u16;
    state.persist_metadata().map_err(|error| error.to_string())
}

fn apply_terminate(payload: &[u8], state: &Arc<Mutex<SharedState>>) -> Result<(), String> {
    let mode = u16::from_le_bytes(payload[0..2].try_into().unwrap());
    let (kind, signal) = match mode {
        0 => (2, libc::SIGTERM),
        1 => (3, libc::SIGKILL),
        _ => return Err("unsupported termination mode".to_owned()),
    };
    signal_descendants(kind, signal, state).map_err(|error| error.detail())?;
    Ok(())
}

fn apply_journal_acknowledgement(
    payload: &[u8],
    shared: &Arc<Mutex<SharedState>>,
) -> Result<(), String> {
    let requested = u64::from_le_bytes(
        payload
            .try_into()
            .map_err(|_| "journal acknowledgement effect must be 8 bytes".to_owned())?,
    );
    let mut state = lock_state(shared).map_err(|error| error.to_string())?;
    validate_received_watermark(requested, state.journal.latest_event_id())
        .map_err(|error| error.to_string())?;
    let durable = state
        .acknowledgement
        .advance(requested)
        .map_err(|error| error.to_string())?;
    if let Err(error) = state.journal.apply_retention_through(durable) {
        eprintln!("session-host: journal retention was not applied: {error}");
    }
    Ok(())
}

fn list_processes(state: &Arc<Mutex<SharedState>>) -> Result<Vec<u8>, String> {
    let descendants = Arc::clone(&lock_state(state).map_err(|error| error.to_string())?.descendants);
    let processes = lock_descendants(&descendants)
        .map_err(|error| error.to_string())?
        .list_processes().map_err(|error| error.to_string())?;
    protocol::encode_process_list(&processes).map_err(|error| error.to_string())
}

fn apply_process_signal(
    token: u64, kind: u16, signal: libc::c_int, state: &Arc<Mutex<SharedState>>,
) -> Result<(), String> {
    let descendants = Arc::clone(&lock_state(state).map_err(|error| error.to_string())?.descendants);
    lock_descendants(&descendants).map_err(|error| error.to_string())?
        .signal_process(token, signal).map_err(|error| error.to_string())?;
    lock_state(state).map_err(|error| error.to_string())?
        .append_buffered(JournalEvent::Signal { kind, platform_code: signal })
        .map_err(|error| error.to_string())?;
    Ok(())
}

fn handle_status(
    payload: &[u8],
    state: &Arc<Mutex<SharedState>>,
) -> Result<Vec<u8>, (u32, String)> {
    if !payload.is_empty() {
        return Err((
            ERROR_INVALID_REQUEST,
            "STATUS payload must be empty".to_owned(),
        ));
    }
    let state = match lock_state(state) {
        Ok(state) => state,
        Err(error) => return Err((ERROR_IO, error.to_string())),
    };
    let mut payload = vec![0_u8; 64];
    let state_code: u16 = if state.child_live { 2 } else { 3 };
    payload[0..2].copy_from_slice(&state_code.to_le_bytes());
    let flags = 1_u16
        | if state.child_live { 2 } else { 0 }
        | if state.metadata.sandbox.enforcement == SandboxEnforcement::Landlock {
            4
        } else {
            0
        };
    payload[2..4].copy_from_slice(&flags.to_le_bytes());
    payload[4..8].copy_from_slice(&u32::from(state.metadata.current_cols).to_le_bytes());
    payload[8..12].copy_from_slice(&u32::from(state.metadata.current_rows).to_le_bytes());
    payload[12..20].copy_from_slice(&state.metadata.host_pid.to_le_bytes());
    payload[20..28].copy_from_slice(&state.metadata.child_pid.unwrap_or(u64::MAX).to_le_bytes());
    payload[28..36].copy_from_slice(
        &state
            .journal
            .first_event_id()
            .unwrap_or(u64::MAX)
            .to_le_bytes(),
    );
    payload[36..44].copy_from_slice(
        &state
            .journal
            .latest_event_id()
            .unwrap_or(u64::MAX)
            .to_le_bytes(),
    );
    payload[44..48].copy_from_slice(&state.exit_code.to_le_bytes());
    payload[48..52].copy_from_slice(&state.exit_signal.to_le_bytes());
    payload[52..54].copy_from_slice(&protocol::JOURNAL_VERSION.to_le_bytes());
    payload[54..56].copy_from_slice(&protocol::CONTROL_VERSION.to_le_bytes());
    Ok(payload)
}

fn apply_foreground_signal(
    kind: u16,
    signal: libc::c_int,
    state: &Arc<Mutex<SharedState>>,
) -> Result<(), String> {
    let mut state = lock_state(state).map_err(|error| error.to_string())?;
    state
        .append_buffered(JournalEvent::Signal {
            kind,
            platform_code: signal,
        })
        .map_err(|error| error.to_string())?;
    let foreground_group = unsafe { libc::tcgetpgrp(state.master.as_raw_fd()) };
    if foreground_group < 0 {
        return Err(io::Error::last_os_error().to_string());
    }
    let descendants = Arc::clone(&state.descendants);
    drop(state);
    apply_owned_foreground_signal(foreground_group, signal, &descendants)
}

#[cfg(target_os = "linux")]
fn apply_owned_foreground_signal(
    foreground_group: libc::pid_t,
    signal: libc::c_int,
    descendants: &Arc<Mutex<DescendantTracker>>,
) -> Result<(), String> {
    lock_descendants(descendants)
        .and_then(|mut tracker| tracker.signal_foreground(foreground_group, signal).map_err(HostError::from))
        .map_err(|error| error.to_string())
}

#[cfg(target_os = "macos")]
fn apply_owned_foreground_signal(
    foreground_group: libc::pid_t,
    signal: libc::c_int,
    _descendants: &Arc<Mutex<DescendantTracker>>,
) -> Result<(), String> {
    if unsafe { libc::kill(-foreground_group, signal) } != 0 {
        return Err(io::Error::last_os_error().to_string());
    }
    Ok(())
}

struct DescendantSignalFailure {
    journal: Option<String>,
    discovery: Option<String>,
    delivery: DescendantDelivery,
}

#[derive(Debug, Default)]
struct DescendantDelivery {
    attempted: usize,
    succeeded: usize,
    failures: Vec<String>,
}

impl DescendantSignalFailure {
    fn journal(detail: String) -> Self {
        Self {
            journal: Some(detail),
            discovery: None,
            delivery: DescendantDelivery::default(),
        }
    }

    fn discovery(detail: String) -> Self {
        Self {
            journal: None,
            discovery: Some(detail),
            delivery: DescendantDelivery::default(),
        }
    }

    fn detail(&self) -> String {
        let mut details = Vec::new();
        if let Some(journal) = &self.journal {
            details.push(format!("failed to record descendant signal: {journal}"));
        }
        if let Some(discovery) = &self.discovery {
            details.push(format!("process discovery failed: {discovery}"));
        }
        if !self.delivery.failures.is_empty() {
            details.push(format!(
                "descendant signal delivery failed ({} attempted, {} succeeded): {}",
                self.delivery.attempted,
                self.delivery.succeeded,
                self.delivery.failures.join("; ")
            ));
        }
        details.join("; ")
    }
}

fn signal_descendants(
    kind: u16,
    signal: libc::c_int,
    state: &Arc<Mutex<SharedState>>,
) -> Result<DescendantDelivery, DescendantSignalFailure> {
    let descendants = {
        let state = lock_state(state)
            .map_err(|error| DescendantSignalFailure::journal(error.to_string()))?;
        Arc::clone(&state.descendants)
    };
    let mut tracker = lock_descendants(&descendants)
        .map_err(|error| DescendantSignalFailure::journal(error.to_string()))?;
    #[cfg(target_os = "linux")]
    let delivery = tracker.signal(signal)
        .map_err(|error| DescendantSignalFailure::discovery(error.to_string()))?;
    #[cfg(target_os = "macos")]
    let delivery = {
        tracker
            .refresh()
            .map_err(|error| DescendantSignalFailure::discovery(error.to_string()))?;
        let mut delivery = DescendantDelivery::default();
        for (pid, _) in tracker.snapshot() {
            delivery.attempted += 1;
            if unsafe { libc::kill(pid, signal) } == 0 {
                delivery.succeeded += 1;
            } else {
                let error = io::Error::last_os_error();
                if error.raw_os_error() == Some(libc::ESRCH) {
                    delivery.succeeded += 1;
                } else {
                    delivery.failures.push(format!("pid {pid}: {error}"));
                }
            }
        }

        delivery
    };
    let journal_error = lock_state(state)
        .and_then(|mut state| {
            state
                .append_buffered(JournalEvent::Signal {
                    kind,
                    platform_code: signal,
                })
                .map(|_| ())
        })
        .err()
        .map(|error| error.to_string());
    if journal_error.is_none() && delivery.failures.is_empty() {
        return Ok(delivery);
    }
    Err(DescendantSignalFailure {
        journal: journal_error,
        discovery: None,
        delivery,
    })
}

fn parse_signal(payload: &[u8]) -> Result<(u16, libc::c_int), &'static str> {
    if payload.len() != 8 {
        return Err("SIGNAL payload must be 8 bytes");
    }
    let kind = u16::from_le_bytes(payload[0..2].try_into().unwrap());
    let flags = u16::from_le_bytes(payload[2..4].try_into().unwrap());
    let platform = host::i32_at(&payload[4..8]);
    if flags != 0 {
        return Err("SIGNAL flags must be zero");
    }
    let signal = match kind {
        1 => libc::SIGINT,
        2 => libc::SIGTERM,
        3 => libc::SIGKILL,
        4 => libc::SIGHUP,
        5 => libc::SIGQUIT,
        0xffff if platform > 0 => platform,
        _ => return Err("unsupported SIGNAL kind or platform signal"),
    };
    if kind != 0xffff && platform != -1 && platform != signal {
        return Err("SIGNAL platform code does not match its portable kind");
    }
    Ok((kind, signal))
}

fn response_error(sequence: u64, code: u32, detail: &str) -> (u16, u64, Vec<u8>) {
    (
        control_message::ERROR,
        sequence,
        host::error_payload(code, detail),
    )
}

fn wait_for_child(pid: libc::pid_t) -> Result<libc::c_int, HostError> {
    loop {
        let mut status = 0;
        let result = unsafe { libc::waitpid(pid, &mut status, 0) };
        if result == pid {
            return Ok(status);
        }
        if result < 0 && io::Error::last_os_error().kind() == io::ErrorKind::Interrupted {
            continue;
        }
        return Err(io::Error::last_os_error().into());
    }
}

fn wait_for_process_tree(
    pid: libc::pid_t,
    state: &Arc<Mutex<SharedState>>,
    signal_ingress: &SignalIngress,
) -> Result<libc::c_int, HostError> {
    let descendants = {
        let state = lock_state(state)?;
        Arc::clone(&state.descendants)
    };
    let mut child_status = None;
    let mut absence = DescendantAbsenceConfirmation::new();

    loop {
        let mut status = 0;
        if child_status.is_none() {
            let result = unsafe { libc::waitpid(pid, &mut status, libc::WNOHANG) };
            if result == pid {
                lock_descendants(&descendants)?.mark_root_reaped();
                child_status = Some(status);
            } else if result < 0 {
                let error = io::Error::last_os_error();
                if error.kind() != io::ErrorKind::Interrupted {
                    return Err(error.into());
                }
            }
        }

        while let Some(signal) = signal_ingress.take_signal() {
            let kind = portable_signal_kind(signal);
            if let Err(failure) = signal_descendants(kind, signal, state) {
                eprintln!(
                    "session-host: external signal {signal} forwarding failed: {}",
                    failure.detail()
                );
            }
        }

        let live = lock_descendants(&descendants)?.is_live()?;
        if let Some(status) = child_status {
            if absence.observe(live) {
                return Ok(status);
            }
        }
        thread::sleep(DESCENDANT_POLL_INTERVAL);
    }
}

fn portable_signal_kind(signal: libc::c_int) -> u16 {
    match signal {
        libc::SIGINT => 1,
        libc::SIGTERM => 2,
        libc::SIGKILL => 3,
        libc::SIGHUP => 4,
        libc::SIGQUIT => 5,
        _ => 0xffff,
    }
}

#[cfg(target_os = "linux")]
fn prepare_descendant_tracking() -> io::Result<()> {
    linux_process_tree::prepare_descendant_tracking()
}

#[cfg(target_os = "macos")]
fn prepare_descendant_tracking() -> io::Result<()> {
    Ok(())
}

#[cfg(target_os = "macos")]
struct DescendantTracker {
    root: libc::pid_t,
    pty: PtySlaveIdentity,
    live: HashMap<libc::pid_t, (u128, u64)>,
    next_token: u64,
}

#[cfg(target_os = "macos")]
impl DescendantTracker {
    fn new(root: libc::pid_t, pty: PtySlaveIdentity) -> io::Result<Self> {
        let processes = macos_processes(pty)?;
        let root_start = processes
            .get(&root)
            .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "PTY child disappeared"))?
            .start;
        Ok(Self {
            root,
            pty,
            live: HashMap::from([(root, (root_start, 1))]),
            next_token: 2,
        })
    }

    fn mark_root_reaped(&mut self) {
        self.live.remove(&self.root);
    }

    fn refresh(&mut self) -> io::Result<()> {
        let processes = macos_processes(self.pty)?;
        self.live.retain(|pid, (start, _)| {
            processes
                .get(pid)
                .is_some_and(|process| process.start == *start)
        });
        loop {
            let mut changed = false;
            for (pid, process) in &processes {
                if !self.live.contains_key(pid)
                    && ((process.session == self.root && self.live.contains_key(&self.root))
                        || process.holds_pty
                        || self.live.contains_key(&process.parent))
                {
                    let token = self.next_token;
                    self.next_token = token.checked_add(1)
                        .ok_or_else(|| io::Error::other("process token space exhausted"))?;
                    self.live.insert(*pid, (process.start, token));
                    changed = true;
                }
            }
            if !changed {
                break;
            }
        }
        Ok(())
    }

    fn snapshot(&self) -> Vec<(libc::pid_t, u128)> {
        self.live
            .iter()
            .map(|(pid, (start, _))| (*pid, *start))
            .collect()
    }

    fn list_processes(&mut self) -> io::Result<Vec<protocol::ListedProcess>> {
        self.refresh()?;
        let mut processes = Vec::with_capacity(self.live.len());
        for (&pid, &(_, token)) in &self.live {
            processes.push(protocol::ListedProcess {
                token, pid: pid as u64, original_root: token == 1,
            });
        }
        processes.sort_unstable_by_key(|process| process.token);
        Ok(processes)
    }

    fn signal_process(&mut self, token: u64, signal: libc::c_int) -> io::Result<()> {
        self.refresh()?;
        let pid = self.live.iter().find_map(|(pid, (_, candidate))| (*candidate == token).then_some(*pid))
            .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "unknown or exited process token"))?;
        let current = macos_processes(self.pty)?;
        if !current.get(&pid).is_some_and(|process| {
            self.live.get(&pid).is_some_and(|(start, _)| *start == process.start)
        }) {
            self.live.remove(&pid);
            return Err(io::Error::new(io::ErrorKind::NotFound, "process identity exited or changed"));
        }
        if unsafe { libc::kill(pid, signal) } != 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    }

    fn is_live(&mut self) -> io::Result<bool> {
        self.refresh()?;
        Ok(!self.live.is_empty())
    }
}

#[cfg(target_os = "macos")]
struct MacProcess {
    parent: libc::pid_t,
    session: libc::pid_t,
    start: u128,
    holds_pty: bool,
}

#[cfg(target_os = "macos")]
#[repr(C)]
struct MacProcFileInfo {
    open_flags: u32,
    status: u32,
    offset: libc::off_t,
    file_type: i32,
    guard_flags: u32,
}

#[cfg(target_os = "macos")]
#[repr(C)]
struct MacVnodeFdInfoWithPath {
    file: MacProcFileInfo,
    vnode: libc::vnode_info_path,
}

#[cfg(target_os = "macos")]
fn macos_processes(pty: PtySlaveIdentity) -> io::Result<HashMap<libc::pid_t, MacProcess>> {
    let mut capacity = 4096_usize;
    let pids = loop {
        let mut pids = vec![0 as libc::pid_t; capacity];
        let count = unsafe {
            libc::proc_listallpids(
                pids.as_mut_ptr().cast(),
                (pids.len() * std::mem::size_of::<libc::pid_t>()) as libc::c_int,
            )
        };
        if count < 0 {
            return Err(io::Error::last_os_error());
        }
        if count as usize >= capacity {
            capacity *= 2;
            continue;
        }
        pids.truncate(count as usize);
        break pids;
    };

    let mut processes = HashMap::new();
    for pid in pids {
        let mut info: libc::proc_bsdinfo = unsafe { std::mem::zeroed() };
        let size = std::mem::size_of::<libc::proc_bsdinfo>() as libc::c_int;
        if unsafe {
            libc::proc_pidinfo(
                pid,
                libc::PROC_PIDTBSDINFO,
                0,
                (&mut info as *mut libc::proc_bsdinfo).cast(),
                size,
            )
        } != size
        {
            continue;
        }
        let session = unsafe { libc::getsid(pid) };
        if session < 0 {
            continue;
        }
        processes.insert(
            pid,
            MacProcess {
                parent: info.pbi_ppid as libc::pid_t,
                session,
                start: (u128::from(info.pbi_start_tvsec) << 64) | u128::from(info.pbi_start_tvusec),
                holds_pty: macos_process_holds_pty(pid, info.pbi_nfiles, pty),
            },
        );
    }
    Ok(processes)
}

#[cfg(target_os = "macos")]
fn macos_process_holds_pty(pid: libc::pid_t, file_count: u32, pty: PtySlaveIdentity) -> bool {
    let capacity = file_count.saturating_add(16) as usize;
    let mut files: Vec<std::mem::MaybeUninit<libc::proc_fdinfo>> = Vec::with_capacity(capacity);
    let bytes = unsafe {
        libc::proc_pidinfo(
            pid,
            libc::PROC_PIDLISTFDS,
            0,
            files.as_mut_ptr().cast(),
            (capacity * std::mem::size_of::<libc::proc_fdinfo>()) as libc::c_int,
        )
    };
    if bytes <= 0 {
        return false;
    }
    let count = bytes as usize / std::mem::size_of::<libc::proc_fdinfo>();
    unsafe {
        files.set_len(count);
    }
    for file in &files[..count] {
        let file = unsafe { file.assume_init_ref() };
        if file.proc_fdtype != libc::PROX_FDTYPE_VNODE as u32 {
            continue;
        }
        let mut vnode: MacVnodeFdInfoWithPath = unsafe { std::mem::zeroed() };
        let size = std::mem::size_of::<MacVnodeFdInfoWithPath>() as libc::c_int;
        if unsafe {
            libc::proc_pidfdinfo(
                pid,
                file.proc_fd,
                2,
                (&mut vnode as *mut MacVnodeFdInfoWithPath).cast(),
                size,
            )
        } != size
        {
            continue;
        }
        let stat = &vnode.vnode.vip_vi.vi_stat;
        if stat.vst_rdev as libc::dev_t == pty.device && stat.vst_ino as libc::ino_t == pty.inode {
            return true;
        }
    }
    false
}

#[cfg(target_os = "linux")]
type DescendantTracker = linux_process_tree::ActiveProcessTreeBackend;

fn decode_wait_status(status: libc::c_int) -> (i32, i32) {
    if libc::WIFEXITED(status) {
        (libc::WEXITSTATUS(status), -1)
    } else if libc::WIFSIGNALED(status) {
        (i32::MIN, libc::WTERMSIG(status))
    } else {
        (i32::MIN, -1)
    }
}

fn remove_stale_endpoint(path: &Path) -> Result<(), HostError> {
    match fs::symlink_metadata(path) {
        Ok(metadata) if metadata.file_type().is_socket() => match UnixStream::connect(path) {
            Ok(_) => Err(HostError::InvalidOptions(format!(
                "another session host is listening at {}",
                path.display()
            ))),
            Err(error) if error.kind() == io::ErrorKind::ConnectionRefused => {
                fs::remove_file(path)?;
                Ok(())
            }
            Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
            Err(error) => Err(error.into()),
        },
        Ok(_) => Err(HostError::InvalidOptions(format!(
            "control endpoint path is occupied by a non-socket: {}",
            path.display()
        ))),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error.into()),
    }
}

fn lock_state(state: &Arc<Mutex<SharedState>>) -> Result<MutexGuard<'_, SharedState>, HostError> {
    state
        .lock()
        .map_err(|_| HostError::Thread("shared state mutex is poisoned".to_owned()))
}

fn lock_descendants(
    descendants: &Arc<Mutex<DescendantTracker>>,
) -> Result<MutexGuard<'_, DescendantTracker>, HostError> {
    descendants
        .lock()
        .map_err(|_| HostError::Thread("descendant tracker mutex is poisoned".to_owned()))
}

fn epoch_millis() -> Result<u64, HostError> {
    let duration = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|error| {
            HostError::InvalidOptions(format!("system clock precedes Unix epoch: {error}"))
        })?;
    u64::try_from(duration.as_millis())
        .map_err(|_| HostError::InvalidOptions("epoch milliseconds exceed u64".to_owned()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::OsString;
    use std::io::Write;

    #[test]
    fn requires_three_consecutive_empty_descendant_observations() {
        let mut confirmation = DescendantAbsenceConfirmation::new();

        assert!(!confirmation.observe(false));
        assert!(!confirmation.observe(false));
        assert!(!confirmation.observe(true));
        assert!(!confirmation.observe(false));
        assert!(!confirmation.observe(false));
        assert!(confirmation.observe(false));
    }

    #[test]
    fn connection_ordinal_exhaustion_fails_closed() {
        let mut next = u64::MAX;

        assert!(take_connection_ordinal(&mut next).is_err());
        assert_eq!(next, u64::MAX);
    }

    #[test]
    fn pty_reader_continues_after_journal_append_failure() {
        let directory = std::env::temp_dir().join(format!(
            "session-host-reader-{}-{}",
            std::process::id(),
            epoch_millis().unwrap()
        ));
        let options = SessionOptions {
            session_id: "reader-journal-failure".to_owned(),
            start_command_id: "command.start".to_owned(),
            session_dir: directory.clone(),
            cwd: PathBuf::from("/tmp"),
            cols: 80,
            rows: 24,
            term: "xterm-256color".to_owned(),
            colorterm: None,
            sandbox_policy: None,
            journal_segment_bytes: 1024,
            journal_max_bytes: 1024 * 1024,
            command: ["/usr/bin/perl", "-e",
                "$SIG{HUP}=q(IGNORE); $|=1; print q(output); \
                 close STDIN; close STDOUT; close STDERR; sleep 30"]
                .into_iter()
                .map(OsString::from)
                .collect(),
        };
        let prepared = PreparedCommand::validate(&options).unwrap();
        let sandbox = PreparedSandbox::prepare(&options).unwrap();
        let mut journal = JournalWriter::create(&directory, JournalConfig::default()).unwrap();
        let acknowledgement = JournalAcknowledgement::open(&directory).unwrap();
        let metadata = initial_metadata(&options, &sandbox, epoch_millis().unwrap()).unwrap();
        let (child_pid, master, descendants) = spawn_pty(&prepared, 80, 24, sandbox, &mut journal).unwrap();
        journal.finish_durably(0).unwrap();
        let state = Arc::new(Mutex::new(SharedState {
            journal,
            metadata,
            master: File::open("/dev/null").unwrap(),
            pty_closed: false,
            accepted_sequence_high_watermark: None,
            active_server_connection_floor: None,
            operation_order: Arc::new(Mutex::new(())),
            operations: Arc::new(OperationCoordinator::new()),
            acknowledgement,
            descendants: Arc::new(Mutex::new(descendants)),
            child_live: true,
            exit_code: i32::MIN,
            exit_signal: -1,
        }));

        assert!(copy_pty_output(master, Arc::clone(&state)).is_ok());
        assert!(lock_state(&state).unwrap().pty_closed);
        assert_eq!(apply_input(&[0; 16], &state).unwrap_err(), "PTY is closed");
        assert_eq!(apply_resize(&[80, 0, 0, 0, 24, 0, 0, 0], &state).unwrap_err(), "PTY is closed");
        let processes = list_processes(&state).unwrap();
        let root = processes[4..].chunks_exact(24).find(|entry| {
            u64::from_le_bytes(entry[8..16].try_into().unwrap()) == child_pid as u64
        }).unwrap();
        let token = u64::from_le_bytes(root[..8].try_into().unwrap());
        assert_eq!(unsafe { libc::kill(child_pid, 0) }, 0);
        assert!(apply_process_signal(token, 3, libc::SIGKILL, &state).is_err());
        let status = wait_for_child(child_pid).unwrap();
        assert!(libc::WIFSIGNALED(status));
        assert_eq!(libc::WTERMSIG(status), libc::SIGKILL);
        {
            let mut state = lock_state(&state).unwrap();
            state.journal = JournalWriter::create(&directory.join("recovered"), JournalConfig::default()).unwrap();
            let before = state.journal.latest_event_id();
            state.close_pty();
            assert_eq!(state.journal.latest_event_id(), before, "closure is not retried after append failure");
            assert!(state.operations.register_operation().unwrap().is_some());
        }
        drop(state);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn command_result_append_failure_does_not_authorize_server_replay() {
        let directory = std::env::temp_dir().join(format!(
            "session-host-command-result-failure-{}-{}",
            std::process::id(),
            epoch_millis().unwrap()
        ));
        let options = SessionOptions {
            session_id: "command-result-failure".to_owned(),
            start_command_id: "command.start".to_owned(),
            session_dir: directory.clone(),
            cwd: PathBuf::from("/tmp"),
            cols: 80,
            rows: 24,
            term: "xterm-256color".to_owned(),
            colorterm: None,
            sandbox_policy: None,
            journal_segment_bytes: 1024,
            journal_max_bytes: 1024 * 1024,
            command: ["/bin/sh", "-c", "sleep 0.2"]
                .into_iter()
                .map(OsString::from)
                .collect(),
        };
        let prepared = PreparedCommand::validate(&options).unwrap();
        let sandbox = PreparedSandbox::prepare(&options).unwrap();
        let mut journal = JournalWriter::create(&directory, JournalConfig::default()).unwrap();
        let acknowledgement = JournalAcknowledgement::open(&directory).unwrap();
        let metadata = initial_metadata(&options, &sandbox, epoch_millis().unwrap()).unwrap();
        let (child_pid, master, descendants) = spawn_pty(&prepared, 80, 24, sandbox, &mut journal).unwrap();
        let final_event_id = journal.finish_durably(0).unwrap();
        let journal_before_operation = fs::read(directory.join("00000001.cbor")).unwrap();
        let state = Arc::new(Mutex::new(SharedState {
            journal,
            metadata,
            master,
            pty_closed: false,
            accepted_sequence_high_watermark: None,
            active_server_connection_floor: None,
            operation_order: Arc::new(Mutex::new(())),
            operations: Arc::new(OperationCoordinator::new()),
            acknowledgement,
            descendants: Arc::new(Mutex::new(descendants)),
            child_live: true,
            exit_code: i32::MIN,
            exit_signal: -1,
        }));
        let sequence = 42;
        let payload = protocol::encode_operation_control_payload(
            control_message::ACK_JOURNAL,
            protocol::OperationSource::Server,
            Some(&[0x80]),
            &final_event_id.to_le_bytes(),
        )
        .unwrap();
        let frame = OwnedControlFrame {
            message_type: control_message::ACK_JOURNAL,
            payload_schema_version: 3,
            sequence,
            payload,
        };
        let (mut host_stream, mut client_stream) = UnixStream::pair().unwrap();

        handle_operation(&mut host_stream, &frame, 1, &state).unwrap();
        let received = host::read_control_frame(&mut client_stream)
            .unwrap()
            .unwrap();
        assert_eq!(received.message_type, control_message::RECEIVED);
        assert_eq!(received.sequence, sequence);
        assert!(received.payload.is_empty());
        {
            let state = lock_state(&state).unwrap();
            assert_eq!(state.accepted_sequence_high_watermark, Some(sequence));
            assert_eq!(
                state.acknowledgement.acknowledged_event_id(),
                Some(final_event_id)
            );
        }
        assert_eq!(
            fs::read(directory.join("00000001.cbor")).unwrap(),
            journal_before_operation
        );

        handle_operation(&mut host_stream, &frame, 1, &state).unwrap();
        let rejected = host::read_control_frame(&mut client_stream)
            .unwrap()
            .unwrap();
        assert_eq!(rejected.message_type, control_message::RECEIVED);
        assert_eq!(rejected.sequence, sequence);
        assert_eq!(host::u32_at(&rejected.payload[0..4]), ERROR_INVALID_REQUEST);
        assert_eq!(
            fs::read(directory.join("00000001.cbor")).unwrap(),
            journal_before_operation
        );

        assert!(libc::WIFEXITED(wait_for_child(child_pid).unwrap()));
        drop(host_stream);
        drop(client_stream);
        drop(state);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn validates_portable_and_platform_signals() {
        assert_eq!(
            parse_signal(&host::signal_payload(1, -1)),
            Ok((1, libc::SIGINT))
        );
        assert_eq!(
            parse_signal(&host::signal_payload(0xffff, libc::SIGUSR1)),
            Ok((0xffff, libc::SIGUSR1))
        );
        assert!(parse_signal(&host::signal_payload(1, libc::SIGTERM)).is_err());
    }

    #[test]
    fn operation_admission_stays_open_until_finalization() {
        let coordinator = Arc::new(OperationCoordinator::new());
        let first = coordinator.register_operation().unwrap().unwrap();
        let second = coordinator.register_operation().unwrap().unwrap();
        coordinator.close_admission().unwrap();
        assert!(coordinator.register_operation().unwrap().is_none());
        drop(first);
        drop(second);
        coordinator.wait_for_operations().unwrap();
    }

    #[test]
    fn operation_guard_releases_coordinator_wait_after_registration() {
        let coordinator = Arc::new(OperationCoordinator::new());
        let active = coordinator.register_operation().unwrap().unwrap();
        let waiting = Arc::clone(&coordinator);
        let thread = thread::spawn(move || waiting.wait_for_operations().unwrap());
        thread::sleep(Duration::from_millis(10));
        drop(active);
        thread.join().unwrap();
    }

    #[test]
    fn received_delivery_timeout_closes_a_stalled_connection() {
        let (mut writer, _reader) = UnixStream::pair().unwrap();
        writer.set_nonblocking(true).unwrap();
        let filler = [0_u8; 4096];
        loop {
            match writer.write(&filler) {
                Ok(_) => {}
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => break,
                Err(error) => panic!("failed to fill Unix socket: {error}"),
            }
        }
        writer.set_nonblocking(false).unwrap();
        writer
            .set_write_timeout(Some(Duration::from_millis(20)))
            .unwrap();
        let frame = OwnedControlFrame {
            message_type: control_message::INPUT,
            payload_schema_version: 3,
            sequence: 1,
            payload: Vec::new(),
        };

        let started = std::time::Instant::now();
        send_received(&mut writer, &frame, None).unwrap();

        assert!(started.elapsed() < Duration::from_secs(1));
        assert!(writer.write(&[1]).is_err());
    }
}
