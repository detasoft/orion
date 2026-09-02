use std::collections::HashMap;
use std::ffi::{CString, OsStr};
use std::fs::{self, File};
use std::io::{self, Read, Write};
use std::os::fd::{AsRawFd, FromRawFd};
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::FileTypeExt;
#[cfg(target_os = "linux")]
use std::os::unix::fs::MetadataExt;
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use super::PlatformKind;
use crate::cli::{SandboxUnavailable, SessionOptions};
use crate::host::{
    self, ERROR_INVALID_REQUEST, ERROR_INVALID_STATE, ERROR_IO, ERROR_UNSUPPORTED_MESSAGE,
    ERROR_UNSUPPORTED_SCHEMA, HostError, OwnedControlFrame,
};
use crate::journal::{
    self, ControlMetadata, ControlTransport, Durability, JournalConfig, JournalWriter, Metadata,
    SandboxEnforcement, SandboxMetadata, SandboxUnavailablePolicy, SessionState,
};
use crate::protocol::{self, control_message, event_type};

const CONTROL_ENDPOINT: &str = "control.sock";
const CONTROL_DRAIN_TIMEOUT: Duration = Duration::from_secs(1);
const DESCENDANT_ABSENCE_CONFIRMATIONS: usize = 3;
const DESCENDANT_POLL_INTERVAL: Duration = Duration::from_millis(10);
const READ_BUFFER_LENGTH: usize = 64 * 1024;

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
    if options.sandbox_policy.is_some() && options.sandbox_unavailable == SandboxUnavailable::Fail {
        return Err(HostError::Policy(
            "a requested sandbox cannot be enforced until Landlock support is enabled".to_owned(),
        ));
    }

    // A launching shell may send SIGHUP as it exits. The PTY child restores the default before
    // exec, while the host deliberately stays independent of the launcher.
    unsafe {
        libc::signal(libc::SIGHUP, libc::SIG_IGN);
    }

    fs::create_dir_all(&options.session_dir)?;
    let endpoint_path = options.session_dir.join(CONTROL_ENDPOINT);
    remove_stale_endpoint(&endpoint_path)?;
    let listener = UnixListener::bind(&endpoint_path)?;
    let _endpoint_guard = EndpointGuard(endpoint_path);
    listener.set_nonblocking(true)?;

    let journal_id = random_journal_id()?;
    let journal = JournalWriter::create(
        &options.session_dir,
        journal_id,
        JournalConfig {
            durability: Durability::Buffered,
        },
    )?;
    let started_at = epoch_millis()?;
    let mut metadata = initial_metadata(&options, journal_id, started_at)?;
    journal::write_metadata(&options.session_dir, &metadata, Durability::Buffered)?;

    let (child_pid, master, descendants) = spawn_pty(&prepared, options.cols, options.rows)?;
    let child_pid_u64 = u64::try_from(child_pid)
        .map_err(|_| HostError::InvalidOptions("child PID is not representable".to_owned()))?;
    metadata.child_pid = Some(child_pid_u64);
    metadata.state = SessionState::Running;

    let state = Arc::new(Mutex::new(SharedState {
        journal,
        metadata,
        master,
        accepted_inputs: HashMap::new(),
        input_order: Arc::new(Mutex::new(())),
        descendants: Arc::new(Mutex::new(descendants)),
        child_live: true,
        exit_code: i32::MIN,
        exit_signal: -1,
    }));
    {
        let mut state = lock_state(&state)?;
        state.append(event_type::PROCESS_STARTED, &child_pid_u64.to_le_bytes())?;
        state.persist_metadata()?;
    }

    let stop = Arc::new(AtomicBool::new(false));
    let active_connections = Arc::new(AtomicUsize::new(0));
    let accept_thread = spawn_accept_loop(
        listener,
        Arc::clone(&state),
        Arc::clone(&stop),
        Arc::clone(&active_connections),
    );
    let reader_master = {
        let state = lock_state(&state)?;
        state.master.try_clone()?
    };
    let reader_state = Arc::clone(&state);
    let reader_thread = thread::spawn(move || copy_pty_output(reader_master, reader_state));

    let wait_status = wait_for_child(child_pid)?;
    let descendants = {
        let state = lock_state(&state)?;
        Arc::clone(&state.descendants)
    };
    lock_descendants(&descendants)?.mark_root_reaped();
    wait_for_descendants(&descendants)?;
    {
        let mut state = lock_state(&state)?;
        state.child_live = false;
    }
    let reader_result = reader_thread
        .join()
        .map_err(|_| HostError::Thread("PTY reader panicked".to_owned()))?;
    reader_result?;

    let (exit_code, exit_signal) = decode_wait_status(wait_status);
    {
        let mut state = lock_state(&state)?;
        state.exit_code = exit_code;
        state.exit_signal = exit_signal;
        let payload = protocol::process_exited_payload(exit_code, exit_signal);
        state.append(event_type::PROCESS_EXITED, &payload)?;
        state.metadata.state = SessionState::Exited;
        state.persist_metadata()?;
        state.journal.flush()?;
    }

    stop.store(true, Ordering::Release);
    accept_thread
        .join()
        .map_err(|_| HostError::Thread("control accept loop panicked".to_owned()))??;
    let drain_deadline = std::time::Instant::now() + CONTROL_DRAIN_TIMEOUT;
    while active_connections.load(Ordering::Acquire) != 0
        && std::time::Instant::now() < drain_deadline
    {
        thread::sleep(Duration::from_millis(1));
    }
    Ok(())
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
    journal_id: [u8; 16],
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
    Ok(Metadata {
        metadata_version: 1,
        journal_format_version: protocol::JOURNAL_VERSION,
        control_protocol_version: protocol::CONTROL_VERSION,
        session_id: options.session_id.clone(),
        journal_id: format_journal_id(journal_id),
        created_at_epoch_millis: started_at,
        session_start_epoch_millis: started_at,
        command,
        cwd,
        host_pid: u64::from(std::process::id()),
        child_pid: None,
        state: SessionState::Starting,
        initial_cols: options.cols,
        initial_rows: options.rows,
        current_cols: options.cols,
        current_rows: options.rows,
        term: options.term.clone(),
        sandbox: SandboxMetadata {
            requested: options.sandbox_policy.is_some(),
            enforcement: SandboxEnforcement::None,
            unavailable_policy: match options.sandbox_unavailable {
                SandboxUnavailable::Fail => SandboxUnavailablePolicy::Fail,
                SandboxUnavailable::RunUnsandboxed => SandboxUnavailablePolicy::RunUnsandboxed,
            },
            read_write_paths: Vec::new(),
            read_only_paths: Vec::new(),
        },
        control: ControlMetadata {
            transport: ControlTransport::UnixDomainSocket,
            endpoint: CONTROL_ENDPOINT.to_owned(),
        },
        active_segment: 1,
        oldest_available_timestamp: None,
        latest_timestamp: None,
    })
}

fn spawn_pty(
    command: &PreparedCommand,
    cols: u16,
    rows: u16,
) -> Result<(libc::pid_t, File, DescendantTracker), HostError> {
    prepare_descendant_tracking()?;
    let mut start_pipe = [-1; 2];
    if unsafe { libc::pipe(start_pipe.as_mut_ptr()) } != 0 {
        return Err(io::Error::last_os_error().into());
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
        }
        return Err(io::Error::last_os_error().into());
    }
    if pid == 0 {
        unsafe {
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
        exec_child(command, &argument_pointers);
    }
    unsafe {
        libc::close(start_pipe[0]);
    }
    let pty = match pty_slave_identity(master) {
        Ok(pty) => pty,
        Err(error) => {
            unsafe {
                libc::close(start_pipe[1]);
                libc::close(master);
                libc::kill(pid, libc::SIGKILL);
            }
            let _ = wait_for_child(pid);
            return Err(error.into());
        }
    };
    let descendants = match DescendantTracker::new(pid, pty) {
        Ok(descendants) => descendants,
        Err(error) => {
            unsafe {
                libc::close(start_pipe[1]);
                libc::close(master);
                libc::kill(pid, libc::SIGKILL);
            }
            let _ = wait_for_child(pid);
            return Err(error.into());
        }
    };
    let release = 1_u8;
    let released = unsafe { libc::write(start_pipe[1], (&release as *const u8).cast(), 1) };
    unsafe {
        libc::close(start_pipe[1]);
    }
    if released != 1 {
        let error = io::Error::last_os_error();
        unsafe {
            libc::close(master);
            libc::kill(pid, libc::SIGKILL);
        }
        let _ = wait_for_child(pid);
        return Err(error.into());
    }
    let master = unsafe { File::from_raw_fd(master) };
    Ok((pid, master, descendants))
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

fn exec_child(command: &PreparedCommand, arguments: &[*const libc::c_char]) -> ! {
    unsafe {
        libc::signal(libc::SIGHUP, libc::SIG_DFL);
        libc::signal(libc::SIGPIPE, libc::SIG_DFL);
        if libc::chdir(command.cwd.as_ptr()) != 0 {
            child_exec_failed(b"session-host: cannot change child working directory\r\n");
        }
        if libc::setenv(c"TERM".as_ptr(), command.term.as_ptr(), 1) != 0 {
            child_exec_failed(b"session-host: cannot set TERM\r\n");
        }
        if let Some(colorterm) = &command.colorterm
            && libc::setenv(c"COLORTERM".as_ptr(), colorterm.as_ptr(), 1) != 0
        {
            child_exec_failed(b"session-host: cannot set COLORTERM\r\n");
        }
        libc::execvp(arguments[0], arguments.as_ptr());
        child_exec_failed(b"session-host: cannot execute child command\r\n");
    }
}

unsafe fn child_exec_failed(message: &[u8]) -> ! {
    unsafe {
        libc::write(libc::STDERR_FILENO, message.as_ptr().cast(), message.len());
        libc::_exit(127);
    }
}

struct SharedState {
    journal: JournalWriter,
    metadata: Metadata,
    master: File,
    accepted_inputs: HashMap<[u8; 16], u64>,
    input_order: Arc<Mutex<()>>,
    descendants: Arc<Mutex<DescendantTracker>>,
    child_live: bool,
    exit_code: i32,
    exit_signal: i32,
}

impl SharedState {
    fn append(&mut self, event: u16, payload: &[u8]) -> Result<u64, HostError> {
        let timestamp = self.journal.append(event, 1, 0, payload)?;
        self.journal.flush()?;
        self.metadata.oldest_available_timestamp = Some(
            self.metadata
                .oldest_available_timestamp
                .unwrap_or(timestamp),
        );
        self.metadata.latest_timestamp = Some(timestamp);
        Ok(timestamp)
    }

    fn persist_metadata(&self) -> Result<(), HostError> {
        journal::write_metadata(
            self.journal.directory(),
            &self.metadata,
            Durability::Buffered,
        )?;
        Ok(())
    }
}

fn copy_pty_output(mut master: File, state: Arc<Mutex<SharedState>>) -> Result<(), HostError> {
    let mut buffer = vec![0_u8; READ_BUFFER_LENGTH];
    loop {
        match master.read(&mut buffer) {
            Ok(0) => return Ok(()),
            Ok(length) => {
                let mut state = lock_state(&state)?;
                state.append(event_type::PTY_OUTPUT, &buffer[..length])?;
                state.persist_metadata()?;
            }
            Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
            Err(error) if error.raw_os_error() == Some(libc::EIO) => return Ok(()),
            Err(error) => return Err(error.into()),
        }
    }
}

fn spawn_accept_loop(
    listener: UnixListener,
    state: Arc<Mutex<SharedState>>,
    stop: Arc<AtomicBool>,
    active_connections: Arc<AtomicUsize>,
) -> thread::JoinHandle<Result<(), HostError>> {
    thread::spawn(move || {
        while !stop.load(Ordering::Acquire) {
            match listener.accept() {
                Ok((stream, _address)) => {
                    stream.set_nonblocking(false)?;
                    let state = Arc::clone(&state);
                    let active_connections = Arc::clone(&active_connections);
                    active_connections.fetch_add(1, Ordering::AcqRel);
                    thread::spawn(move || {
                        let _active_connection = ActiveConnection(active_connections);
                        if let Err(error) = serve_connection(stream, state) {
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

struct ActiveConnection(Arc<AtomicUsize>);

impl Drop for ActiveConnection {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::AcqRel);
    }
}

fn serve_connection(
    mut stream: UnixStream,
    state: Arc<Mutex<SharedState>>,
) -> Result<(), HostError> {
    while let Some(frame) = host::read_control_frame(&mut stream)? {
        let (message_type, payload) = handle_request(&frame, &state);
        host::write_control_frame(&mut stream, message_type, frame.request_id, &payload)?;
    }
    Ok(())
}

fn handle_request(frame: &OwnedControlFrame, state: &Arc<Mutex<SharedState>>) -> (u16, Vec<u8>) {
    if frame.payload_schema_version != 1 {
        return response_error(ERROR_UNSUPPORTED_SCHEMA, "unsupported payload schema");
    }
    match frame.message_type {
        control_message::INPUT => handle_input(&frame.payload, state),
        control_message::RESIZE => handle_resize(&frame.payload, state),
        control_message::SIGNAL => handle_signal(&frame.payload, state),
        control_message::TERMINATE => handle_terminate(&frame.payload, state),
        control_message::STATUS => handle_status(&frame.payload, state),
        control_message::APPEND_EVENT => response_error(
            ERROR_UNSUPPORTED_MESSAGE,
            "ordered harness event ingress is not enabled yet",
        ),
        _ => response_error(ERROR_UNSUPPORTED_MESSAGE, "unsupported control message"),
    }
}

fn handle_input(payload: &[u8], state: &Arc<Mutex<SharedState>>) -> (u16, Vec<u8>) {
    if payload.len() < 16 {
        return response_error(
            ERROR_INVALID_REQUEST,
            "INPUT payload is shorter than its UUID",
        );
    }
    let input_id: [u8; 16] = payload[..16].try_into().unwrap();
    let input_order = match lock_state(state) {
        Ok(state) => Arc::clone(&state.input_order),
        Err(error) => return response_error(ERROR_IO, &error.to_string()),
    };
    let _input_guard = match input_order.lock() {
        Ok(guard) => guard,
        Err(_) => return response_error(ERROR_IO, "input order mutex is poisoned"),
    };
    let mut state = match lock_state(state) {
        Ok(state) => state,
        Err(error) => return response_error(ERROR_IO, &error.to_string()),
    };
    if !state.child_live {
        return response_error(ERROR_INVALID_STATE, "child process has exited");
    }
    if let Some(timestamp) = state.accepted_inputs.get(&input_id) {
        return (
            control_message::DUPLICATE,
            host::timestamp_payload(*timestamp).to_vec(),
        );
    }
    let timestamp = match state.append(event_type::PTY_INPUT, payload) {
        Ok(timestamp) => timestamp,
        Err(error) => return response_error(ERROR_IO, &error.to_string()),
    };
    state.accepted_inputs.insert(input_id, timestamp);
    let mut master = match state.master.try_clone() {
        Ok(master) => master,
        Err(error) => return response_error(ERROR_IO, &error.to_string()),
    };
    if let Err(error) = state.persist_metadata() {
        return response_error(ERROR_IO, &error.to_string());
    }
    drop(state);
    if let Err(error) = master.write_all(&payload[16..]) {
        return response_error(ERROR_IO, &error.to_string());
    }
    (
        control_message::ACCEPTED,
        host::timestamp_payload(timestamp).to_vec(),
    )
}

fn handle_resize(payload: &[u8], state: &Arc<Mutex<SharedState>>) -> (u16, Vec<u8>) {
    if payload.len() != 8 {
        return response_error(ERROR_INVALID_REQUEST, "RESIZE payload must be 8 bytes");
    }
    let cols = host::u32_at(&payload[0..4]);
    let rows = host::u32_at(&payload[4..8]);
    if cols == 0 || cols > u32::from(u16::MAX) || rows == 0 || rows > u32::from(u16::MAX) {
        return response_error(
            ERROR_INVALID_REQUEST,
            "terminal dimensions are out of range",
        );
    }
    let mut state = match lock_state(state) {
        Ok(state) => state,
        Err(error) => return response_error(ERROR_IO, &error.to_string()),
    };
    if !state.child_live {
        return response_error(ERROR_INVALID_STATE, "child process has exited");
    }
    let timestamp = match state.append(event_type::PTY_RESIZE, payload) {
        Ok(timestamp) => timestamp,
        Err(error) => return response_error(ERROR_IO, &error.to_string()),
    };
    let dimensions = libc::winsize {
        ws_row: rows as u16,
        ws_col: cols as u16,
        ws_xpixel: 0,
        ws_ypixel: 0,
    };
    if unsafe { libc::ioctl(state.master.as_raw_fd(), libc::TIOCSWINSZ, &dimensions) } != 0 {
        return response_error(ERROR_IO, &io::Error::last_os_error().to_string());
    }
    state.metadata.current_cols = cols as u16;
    state.metadata.current_rows = rows as u16;
    if let Err(error) = state.persist_metadata() {
        return response_error(ERROR_IO, &error.to_string());
    }
    (
        control_message::ACCEPTED,
        host::timestamp_payload(timestamp).to_vec(),
    )
}

fn handle_signal(payload: &[u8], state: &Arc<Mutex<SharedState>>) -> (u16, Vec<u8>) {
    let (kind, signal) = match parse_signal(payload) {
        Ok(signal) => signal,
        Err(detail) => return response_error(ERROR_INVALID_REQUEST, detail),
    };
    apply_foreground_signal(kind, signal, state)
}

fn handle_terminate(payload: &[u8], state: &Arc<Mutex<SharedState>>) -> (u16, Vec<u8>) {
    if payload.len() != 8 {
        return response_error(ERROR_INVALID_REQUEST, "TERMINATE payload must be 8 bytes");
    }
    let mode = u16::from_le_bytes(payload[0..2].try_into().unwrap());
    let reserved = u16::from_le_bytes(payload[2..4].try_into().unwrap());
    if reserved != 0 || mode > 1 {
        return response_error(
            ERROR_INVALID_REQUEST,
            "invalid TERMINATE mode or reserved field",
        );
    }
    if mode == 1 {
        return apply_descendant_signal(3, libc::SIGKILL, state);
    }
    let grace_millis = host::u32_at(&payload[4..8]);
    let response = apply_descendant_signal(2, libc::SIGTERM, state);
    if response.0 == control_message::ACCEPTED {
        let state = Arc::clone(state);
        thread::spawn(move || {
            thread::sleep(Duration::from_millis(u64::from(grace_millis)));
            let _ = apply_descendant_signal(3, libc::SIGKILL, &state);
        });
    }
    response
}

fn handle_status(payload: &[u8], state: &Arc<Mutex<SharedState>>) -> (u16, Vec<u8>) {
    if !payload.is_empty() {
        return response_error(ERROR_INVALID_REQUEST, "STATUS payload must be empty");
    }
    let state = match lock_state(state) {
        Ok(state) => state,
        Err(error) => return response_error(ERROR_IO, &error.to_string()),
    };
    let mut payload = vec![0_u8; 64];
    let state_code: u16 = match state.metadata.state {
        SessionState::Starting => 1,
        SessionState::Running => 2,
        SessionState::Exited => 3,
        SessionState::Failed => 4,
    };
    payload[0..2].copy_from_slice(&state_code.to_le_bytes());
    let flags = 1_u16 | if state.child_live { 2 } else { 0 };
    payload[2..4].copy_from_slice(&flags.to_le_bytes());
    payload[4..8].copy_from_slice(&u32::from(state.metadata.current_cols).to_le_bytes());
    payload[8..12].copy_from_slice(&u32::from(state.metadata.current_rows).to_le_bytes());
    payload[12..20].copy_from_slice(&state.metadata.host_pid.to_le_bytes());
    payload[20..28].copy_from_slice(&state.metadata.child_pid.unwrap_or(u64::MAX).to_le_bytes());
    payload[28..36].copy_from_slice(
        &state
            .metadata
            .oldest_available_timestamp
            .unwrap_or(u64::MAX)
            .to_le_bytes(),
    );
    payload[36..44].copy_from_slice(
        &state
            .metadata
            .latest_timestamp
            .unwrap_or(u64::MAX)
            .to_le_bytes(),
    );
    payload[44..48].copy_from_slice(&state.exit_code.to_le_bytes());
    payload[48..52].copy_from_slice(&state.exit_signal.to_le_bytes());
    payload[52..54].copy_from_slice(&protocol::JOURNAL_VERSION.to_le_bytes());
    payload[54..56].copy_from_slice(&protocol::CONTROL_VERSION.to_le_bytes());
    (control_message::STATUS_RESPONSE, payload)
}

fn apply_foreground_signal(
    kind: u16,
    signal: libc::c_int,
    state: &Arc<Mutex<SharedState>>,
) -> (u16, Vec<u8>) {
    let mut state = match lock_state(state) {
        Ok(state) => state,
        Err(error) => return response_error(ERROR_IO, &error.to_string()),
    };
    if !state.child_live {
        return response_error(ERROR_INVALID_STATE, "child process has exited");
    }
    let payload = host::signal_payload(kind, signal);
    let timestamp = match state.append(event_type::SIGNAL, &payload) {
        Ok(timestamp) => timestamp,
        Err(error) => return response_error(ERROR_IO, &error.to_string()),
    };
    let foreground_group = unsafe { libc::tcgetpgrp(state.master.as_raw_fd()) };
    if foreground_group < 0 {
        return response_error(ERROR_IO, &io::Error::last_os_error().to_string());
    }
    if let Err(error) = state.persist_metadata() {
        return response_error(ERROR_IO, &error.to_string());
    }
    drop(state);
    if unsafe { libc::kill(-foreground_group, signal) } != 0 {
        return response_error(ERROR_IO, &io::Error::last_os_error().to_string());
    }
    (
        control_message::ACCEPTED,
        host::timestamp_payload(timestamp).to_vec(),
    )
}

fn apply_descendant_signal(
    kind: u16,
    signal: libc::c_int,
    state: &Arc<Mutex<SharedState>>,
) -> (u16, Vec<u8>) {
    let mut state = match lock_state(state) {
        Ok(state) => state,
        Err(error) => return response_error(ERROR_IO, &error.to_string()),
    };
    if !state.child_live {
        return response_error(ERROR_INVALID_STATE, "child process has exited");
    }
    let payload = host::signal_payload(kind, signal);
    let timestamp = match state.append(event_type::SIGNAL, &payload) {
        Ok(timestamp) => timestamp,
        Err(error) => return response_error(ERROR_IO, &error.to_string()),
    };
    let descendants = Arc::clone(&state.descendants);
    if let Err(error) = state.persist_metadata() {
        return response_error(ERROR_IO, &error.to_string());
    }
    drop(state);
    if let Err(error) = lock_descendants(&descendants).and_then(|mut tracker| tracker.signal(signal)) {
        return response_error(ERROR_IO, &error.to_string());
    }
    (
        control_message::ACCEPTED,
        host::timestamp_payload(timestamp).to_vec(),
    )
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

fn response_error(code: u32, detail: &str) -> (u16, Vec<u8>) {
    (control_message::ERROR, host::error_payload(code, detail))
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

fn wait_for_descendants(descendants: &Arc<Mutex<DescendantTracker>>) -> Result<(), HostError> {
    let mut absence = DescendantAbsenceConfirmation::new();
    loop {
        let live = lock_descendants(descendants)?.is_live()?;
        if absence.observe(live) {
            return Ok(());
        }
        thread::sleep(DESCENDANT_POLL_INTERVAL);
    }
}

#[cfg(target_os = "linux")]
fn prepare_descendant_tracking() -> io::Result<()> {
    if unsafe { libc::prctl(libc::PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0) } != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

#[cfg(target_os = "macos")]
fn prepare_descendant_tracking() -> io::Result<()> {
    Ok(())
}

#[cfg(target_os = "macos")]
struct DescendantTracker {
    root: libc::pid_t,
    pty: PtySlaveIdentity,
    live: HashMap<libc::pid_t, u128>,
    termination_signal: Option<libc::c_int>,
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
            live: HashMap::from([(root, root_start)]),
            termination_signal: None,
        })
    }

    fn mark_root_reaped(&mut self) {
        self.live.remove(&self.root);
    }

    fn refresh(&mut self) -> io::Result<()> {
        let processes = macos_processes(self.pty)?;
        self.live
            .retain(|pid, start| processes.get(pid).is_some_and(|process| process.start == *start));
        loop {
            let mut changed = false;
            for (pid, process) in &processes {
                if !self.live.contains_key(pid)
                    && (process.session == self.root
                        || process.holds_pty
                        || self.live.contains_key(&process.parent))
                {
                    self.live.insert(*pid, process.start);
                    changed = true;
                }
            }
            if !changed {
                break;
            }
        }
        self.apply_termination_signal();
        Ok(())
    }

    fn apply_termination_signal(&self) {
        let Some(signal) = self.termination_signal else {
            return;
        };
        for pid in self.live.keys() {
            unsafe {
                libc::kill(*pid, signal);
            }
        }
    }

    fn signal(&mut self, signal: libc::c_int) -> Result<(), HostError> {
        self.termination_signal = Some(signal);
        self.refresh()?;
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
                start: (u128::from(info.pbi_start_tvsec) << 64)
                    | u128::from(info.pbi_start_tvusec),
                holds_pty: macos_process_holds_pty(pid, info.pbi_nfiles, pty),
            },
        );
    }
    Ok(processes)
}

#[cfg(target_os = "macos")]
fn macos_process_holds_pty(
    pid: libc::pid_t,
    file_count: u32,
    pty: PtySlaveIdentity,
) -> bool {
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
struct DescendantTracker {
    root: libc::pid_t,
    root_reaped: bool,
    pty: PtySlaveIdentity,
    live: HashMap<libc::pid_t, u128>,
    termination_signal: Option<libc::c_int>,
}

#[cfg(target_os = "linux")]
impl DescendantTracker {
    fn new(root: libc::pid_t, pty: PtySlaveIdentity) -> io::Result<Self> {
        let processes = linux_processes(pty)?;
        let root_start = processes
            .get(&root)
            .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "PTY child disappeared"))?
            .start;
        Ok(Self {
            root,
            root_reaped: false,
            pty,
            live: HashMap::from([(root, root_start)]),
            termination_signal: None,
        })
    }

    fn mark_root_reaped(&mut self) {
        self.root_reaped = true;
        self.live.remove(&self.root);
    }

    fn refresh(&mut self) -> io::Result<()> {
        if self.root_reaped {
            loop {
                let result = unsafe { libc::waitpid(-1, std::ptr::null_mut(), libc::WNOHANG) };
                if result <= 0 {
                    break;
                }
            }
        }
        let processes = linux_processes(self.pty)?;
        self.live
            .retain(|pid, start| processes.get(pid).is_some_and(|process| process.start == *start));
        let host_pid = std::process::id() as libc::pid_t;
        loop {
            let mut changed = false;
            for (pid, process) in &processes {
                if !self.live.contains_key(pid)
                    && (process.parent == host_pid
                        || process.session == self.root
                        || process.holds_pty
                        || self.live.contains_key(&process.parent))
                {
                    self.live.insert(*pid, process.start);
                    changed = true;
                }
            }
            if !changed {
                break;
            }
        }
        if let Some(signal) = self.termination_signal {
            for pid in self.live.keys() {
                unsafe {
                    libc::kill(*pid, signal);
                }
            }
        }
        Ok(())
    }

    fn signal(&mut self, signal: libc::c_int) -> Result<(), HostError> {
        self.termination_signal = Some(signal);
        self.refresh()?;
        Ok(())
    }

    fn is_live(&mut self) -> io::Result<bool> {
        self.refresh()?;
        Ok(!self.live.is_empty())
    }
}

#[cfg(target_os = "linux")]
struct LinuxProcess {
    parent: libc::pid_t,
    session: libc::pid_t,
    start: u128,
    holds_pty: bool,
}

#[cfg(target_os = "linux")]
fn linux_processes(pty: PtySlaveIdentity) -> io::Result<HashMap<libc::pid_t, LinuxProcess>> {
    let mut processes = HashMap::new();
    for entry in fs::read_dir("/proc")? {
        let entry = entry?;
        let Some(pid) = entry
            .file_name()
            .to_str()
            .and_then(|name| name.parse::<libc::pid_t>().ok())
        else {
            continue;
        };
        let Ok(stat) = fs::read_to_string(entry.path().join("stat")) else {
            continue;
        };
        let Some(fields) = stat.rsplit_once(") ").map(|(_, fields)| fields) else {
            continue;
        };
        let fields: Vec<_> = fields.split_ascii_whitespace().collect();
        if fields.len() < 20 {
            continue;
        }
        let (Ok(parent), Ok(session), Ok(start)) = (
            fields[1].parse::<libc::pid_t>(),
            fields[3].parse::<libc::pid_t>(),
            fields[19].parse::<u128>(),
        ) else {
            continue;
        };
        let holds_pty = fs::read_dir(entry.path().join("fd"))
            .into_iter()
            .flatten()
            .filter_map(Result::ok)
            .filter_map(|fd| fs::metadata(fd.path()).ok())
            .any(|metadata| {
                metadata.rdev() as libc::dev_t == pty.device
                    && metadata.ino() as libc::ino_t == pty.inode
            });
        processes.insert(
            pid,
            LinuxProcess {
                parent,
                session,
                start,
                holds_pty,
            },
        );
    }
    Ok(processes)
}

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

fn random_journal_id() -> Result<[u8; 16], HostError> {
    let mut value = [0_u8; 16];
    File::open("/dev/urandom")?.read_exact(&mut value)?;
    value[6] = (value[6] & 0x0f) | 0x40;
    value[8] = (value[8] & 0x3f) | 0x80;
    Ok(value)
}

fn format_journal_id(value: [u8; 16]) -> String {
    let mut result = String::with_capacity(36);
    for (index, byte) in value.iter().enumerate() {
        if matches!(index, 4 | 6 | 8 | 10) {
            result.push('-');
        }
        use std::fmt::Write as _;
        write!(&mut result, "{byte:02x}").unwrap();
    }
    result
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
    fn journal_ids_use_canonical_uuid_text() {
        assert_eq!(
            format_journal_id([
                0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x46, 0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd,
                0xee, 0xff,
            ]),
            "00112233-4455-4677-8899-aabbccddeeff"
        );
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
}
