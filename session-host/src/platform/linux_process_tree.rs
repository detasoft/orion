use std::collections::HashMap;
use std::ffi::OsString;
use std::fs::{self, OpenOptions};
use std::io::{self, Read, Write};
use std::os::fd::{AsFd, AsRawFd, BorrowedFd, FromRawFd, OwnedFd, RawFd};
use std::os::unix::ffi::OsStringExt;
use std::os::unix::fs::MetadataExt;
use std::path::{Component, Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

const CGROUP_MEMBERSHIP_PATH: &str = "/proc/self/cgroup";
const MOUNTINFO_PATH: &str = "/proc/self/mountinfo";
const CGROUP_PROCS: &str = "cgroup.procs";
const CGROUP_EVENTS: &str = "cgroup.events";
const CGROUP_KILL: &str = "cgroup.kill";
static NEXT_SESSION_CGROUP: AtomicU64 = AtomicU64::new(1);
const FALLBACK_EMPTY_CONFIRMATIONS: usize = 3;

pub(super) fn prepare_descendant_tracking() -> io::Result<()> {
    let mut kernel = SystemLinuxKernel;
    verify_subreaper(&mut kernel)
}

trait LinuxKernel {
    fn set_child_subreaper(&mut self) -> io::Result<()>;
    fn child_subreaper(&mut self) -> io::Result<bool>;
    fn open_pidfd(&mut self, pid: libc::pid_t) -> io::Result<OwnedFd>;
    fn send_pidfd_signal(
        &mut self,
        pidfd: BorrowedFd<'_>,
        signal: libc::c_int,
    ) -> io::Result<()>;
    fn pidfd_is_ready(&mut self, pidfd: BorrowedFd<'_>) -> io::Result<bool>;
    fn reap_nonblocking(&mut self) -> io::Result<ReapResult>;
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum ReapResult {
    Reaped(libc::pid_t),
    Running,
    NoChildren,
}

struct SystemLinuxKernel;

impl LinuxKernel for SystemLinuxKernel {
    fn set_child_subreaper(&mut self) -> io::Result<()> {
        let result = unsafe { libc::prctl(libc::PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0) };
        syscall_result(result).map(|_| ())
    }

    fn child_subreaper(&mut self) -> io::Result<bool> {
        let mut value: libc::c_int = 0;
        let result = unsafe {
            libc::prctl(
                libc::PR_GET_CHILD_SUBREAPER,
                &mut value as *mut libc::c_int,
                0,
                0,
                0,
            )
        };
        syscall_result(result).map(|_| value != 0)
    }

    fn open_pidfd(&mut self, pid: libc::pid_t) -> io::Result<OwnedFd> {
        let result = unsafe { libc::syscall(libc::SYS_pidfd_open, pid, 0_u32) };
        if result < 0 {
            return Err(io::Error::last_os_error());
        }
        let raw_fd = RawFd::try_from(result)
            .map_err(|_| io::Error::other("pidfd_open returned an invalid file descriptor"))?;
        Ok(unsafe { OwnedFd::from_raw_fd(raw_fd) })
    }

    fn send_pidfd_signal(
        &mut self,
        pidfd: BorrowedFd<'_>,
        signal: libc::c_int,
    ) -> io::Result<()> {
        let result = unsafe {
            libc::syscall(
                libc::SYS_pidfd_send_signal,
                pidfd.as_raw_fd(),
                signal,
                std::ptr::null::<libc::siginfo_t>(),
                0_u32,
            )
        };
        if result < 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    }

    fn pidfd_is_ready(&mut self, pidfd: BorrowedFd<'_>) -> io::Result<bool> {
        let mut descriptor = libc::pollfd {
            fd: pidfd.as_raw_fd(),
            events: libc::POLLIN,
            revents: 0,
        };
        loop {
            let result = unsafe { libc::poll(&mut descriptor, 1, 0) };
            if result >= 0 {
                return Ok(result > 0 && descriptor.revents & libc::POLLIN != 0);
            }
            let error = io::Error::last_os_error();
            if error.kind() != io::ErrorKind::Interrupted {
                return Err(error);
            }
        }
    }

    fn reap_nonblocking(&mut self) -> io::Result<ReapResult> {
        loop {
            let result = unsafe { libc::waitpid(-1, std::ptr::null_mut(), libc::WNOHANG) };
            if result > 0 {
                return Ok(ReapResult::Reaped(result));
            }
            if result == 0 {
                return Ok(ReapResult::Running);
            }
            let error = io::Error::last_os_error();
            if error.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            if error.raw_os_error() == Some(libc::ECHILD) {
                return Ok(ReapResult::NoChildren);
            }
            return Err(error);
        }
    }
}

fn syscall_result(result: libc::c_int) -> io::Result<libc::c_int> {
    if result < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(result)
}

fn verify_subreaper<K: LinuxKernel>(kernel: &mut K) -> io::Result<()> {
    kernel.set_child_subreaper()?;
    if !kernel.child_subreaper()? {
        return Err(io::Error::other(
            "PR_SET_CHILD_SUBREAPER succeeded but verification was false",
        ));
    }
    Ok(())
}

#[derive(Debug)]
struct CgroupLocation {
    mount_point: PathBuf,
    current: PathBuf,
}

impl CgroupLocation {
    fn discover(membership: &str, mountinfo: &str) -> io::Result<Self> {
        let membership = unified_membership(membership)?;
        let mut saw_cgroup2 = false;
        let mut best: Option<(usize, bool, PathBuf, PathBuf)> = None;
        for line in mountinfo.lines() {
            let fields: Vec<_> = line.split_ascii_whitespace().collect();
            let Some(separator) = fields.iter().position(|field| *field == "-") else {
                continue;
            };
            if separator < 6 || fields.get(separator + 1) != Some(&"cgroup2") {
                continue;
            }
            saw_cgroup2 = true;
            let root = decode_mountinfo_path(fields[3])?;
            let mount_point = decode_mountinfo_path(fields[4])?;
            validate_absolute_path(&root)?;
            validate_absolute_path(&mount_point)?;
            let Ok(relative) = membership.strip_prefix(&root) else {
                continue;
            };
            let depth = root.components().count();
            let writable = fields[5].split(',').any(|option| option == "rw");
            if best.as_ref().is_some_and(|(best_depth, best_writable, _, _)| {
                *best_depth > depth || (*best_depth == depth && (*best_writable || !writable))
            }) {
                continue;
            }
            best = Some((
                depth,
                writable,
                mount_point.clone(),
                mount_point.join(relative),
            ));
        }
        match best {
            Some((_, _, mount_point, current)) => Ok(Self {
                mount_point,
                current,
            }),
            None if saw_cgroup2 => Err(io::Error::other(
                "unified cgroup membership is outside every cgroup2 mount root",
            )),
            None => Err(io::Error::other("no cgroup2 mount is available")),
        }
    }

    fn discover_system() -> io::Result<Self> {
        let membership = fs::read_to_string(CGROUP_MEMBERSHIP_PATH)?;
        let mountinfo = fs::read_to_string(MOUNTINFO_PATH)?;
        Self::discover(&membership, &mountinfo)
    }

    #[cfg(test)]
    fn current(&self) -> &Path {
        &self.current
    }

    #[cfg(test)]
    fn for_test(current: PathBuf) -> Self {
        Self {
            mount_point: current.clone(),
            current,
        }
    }
}

fn unified_membership(contents: &str) -> io::Result<PathBuf> {
    let mut unified = None;
    for line in contents.lines() {
        let mut fields = line.splitn(3, ':');
        let hierarchy = fields.next();
        let controllers = fields.next();
        let path = fields.next();
        if hierarchy != Some("0") || controllers != Some("") {
            continue;
        }
        if unified.is_some() {
            return Err(io::Error::other("multiple unified cgroup membership lines"));
        }
        let path = path.ok_or_else(|| io::Error::other("malformed unified cgroup membership"))?;
        validate_absolute_text_path(path)?;
        unified = Some(PathBuf::from(path));
    }
    unified.ok_or_else(|| io::Error::other("no unified cgroup membership line"))
}

fn validate_absolute_text_path(path: &str) -> io::Result<()> {
    if !path.starts_with('/')
        || path
            .split('/')
            .any(|component| component == "." || component == "..")
    {
        return Err(io::Error::other("unsafe cgroup path"));
    }
    Ok(())
}

fn validate_absolute_path(path: &Path) -> io::Result<()> {
    if !path.is_absolute()
        || path.components().any(|component| {
            matches!(component, Component::CurDir | Component::ParentDir | Component::Prefix(_))
        })
    {
        return Err(io::Error::other("unsafe cgroup path"));
    }
    Ok(())
}

fn decode_mountinfo_path(encoded: &str) -> io::Result<PathBuf> {
    let bytes = encoded.as_bytes();
    let mut decoded = Vec::with_capacity(bytes.len());
    let mut index = 0;
    while index < bytes.len() {
        if bytes[index] != b'\\' {
            decoded.push(bytes[index]);
            index += 1;
            continue;
        }
        if index + 3 >= bytes.len() {
            return Err(io::Error::other("malformed mountinfo escape"));
        }
        let escape = &bytes[index + 1..index + 4];
        let value = match escape {
            b"040" => b' ',
            b"011" => b'\t',
            b"012" => b'\n',
            b"134" => b'\\',
            _ => return Err(io::Error::other("unsupported mountinfo escape")),
        };
        decoded.push(value);
        index += 4;
    }
    Ok(PathBuf::from(OsString::from_vec(decoded)))
}

#[derive(Debug)]
pub(super) struct SessionCgroup {
    path: PathBuf,
    parent_procs: PathBuf,
    owns_path: bool,
    capabilities_verified: bool,
    removed: bool,
}

#[derive(Debug)]
enum CgroupCreationError {
    Clean(io::Error),
    Dirty {
        error: io::Error,
        cgroup: SessionCgroup,
    },
}

impl SessionCgroup {
    fn create(location: &CgroupLocation) -> Result<Self, CgroupCreationError> {
        validate_cgroup_location(location).map_err(CgroupCreationError::Clean)?;
        let parent_procs = location.current.join(CGROUP_PROCS);
        require_cgroup_file(&parent_procs, true).map_err(CgroupCreationError::Clean)?;
        require_cgroup_file(&parent_procs, false).map_err(CgroupCreationError::Clean)?;
        for _ in 0..64 {
            let sequence = NEXT_SESSION_CGROUP.fetch_add(1, Ordering::Relaxed);
            let name = format!("orion-session-{}-{sequence}", std::process::id());
            match Self::create_named(location, &name) {
                Err(CgroupCreationError::Clean(error))
                    if error.kind() == io::ErrorKind::AlreadyExists => {}
                result => return result,
            }
        }
        Err(CgroupCreationError::Clean(io::Error::new(
            io::ErrorKind::AlreadyExists,
            "could not allocate a unique session cgroup",
        )))
    }

    fn create_named(
        location: &CgroupLocation,
        name: &str,
    ) -> Result<Self, CgroupCreationError> {
        validate_child_name(name).map_err(CgroupCreationError::Clean)?;
        let path = location.current.join(name);
        fs::create_dir(&path).map_err(CgroupCreationError::Clean)?;
        let mut cgroup = Self {
            path,
            parent_procs: location.current.join(CGROUP_PROCS),
            owns_path: true,
            capabilities_verified: false,
            removed: false,
        };
        match cgroup.verify_capabilities() {
            Ok(()) => Ok(cgroup),
            Err(error) => {
                match cgroup.remove_created_directory() {
                    Ok(()) => Err(CgroupCreationError::Clean(error)),
                    Err(cleanup) => Err(CgroupCreationError::Dirty {
                        error: combined_error(error, "partial cgroup cleanup failed", cleanup),
                        cgroup,
                    }),
                }
            }
        }
    }

    #[cfg(test)]
    fn open_existing(path: PathBuf) -> io::Result<Self> {
        let parent_procs = path
            .parent()
            .ok_or_else(|| io::Error::other("session cgroup has no parent"))?
            .join(CGROUP_PROCS);
        let mut cgroup = Self {
            path,
            parent_procs,
            owns_path: false,
            capabilities_verified: false,
            removed: false,
        };
        cgroup.verify_capabilities()?;
        Ok(cgroup)
    }

    fn verify_capabilities(&mut self) -> io::Result<()> {
        reject_symlink(&self.path)?;
        require_cgroup_file(&self.path.join(CGROUP_PROCS), false)?;
        require_cgroup_file(&self.path.join(CGROUP_PROCS), true)?;
        require_cgroup_file(&self.path.join(CGROUP_EVENTS), true)?;
        require_cgroup_file(&self.path.join(CGROUP_KILL), false)?;
        self.capabilities_verified = true;
        Ok(())
    }

    fn populated(&self) -> io::Result<bool> {
        let contents = fs::read_to_string(self.path.join(CGROUP_EVENTS))?;
        let mut populated = None;
        for line in contents.lines() {
            let mut fields = line.split_ascii_whitespace();
            if fields.next() != Some("populated") {
                continue;
            }
            let value = fields
                .next()
                .ok_or_else(|| io::Error::other("cgroup.events populated value is missing"))?;
            if fields.next().is_some() || populated.is_some() {
                return Err(io::Error::other("malformed cgroup.events populated entry"));
            }
            populated = match value {
                "0" => Some(false),
                "1" => Some(true),
                _ => return Err(io::Error::other("invalid cgroup.events populated value")),
            };
        }
        populated.ok_or_else(|| io::Error::other("cgroup.events has no populated entry"))
    }

    fn kill(&self) -> io::Result<()> {
        write_cgroup_file(&self.path.join(CGROUP_KILL), "1\n")
    }

    fn process_ids(&self) -> io::Result<Vec<libc::pid_t>> {
        let mut processes = Vec::new();
        collect_process_ids(&self.path, &mut processes)?;
        processes.sort_unstable();
        processes.dedup();
        Ok(processes)
    }

    fn remove_if_empty(&mut self) -> io::Result<()> {
        if self.removed {
            return Ok(());
        }
        if self.populated()? {
            return Err(io::Error::new(
                io::ErrorKind::WouldBlock,
                "session cgroup is still populated",
            ));
        }
        remove_empty_cgroup_tree(&self.path)?;
        self.removed = true;
        Ok(())
    }

    fn remove_created_directory(&mut self) -> io::Result<()> {
        if self.removed {
            return Ok(());
        }
        remove_empty_cgroup_tree(&self.path)?;
        self.removed = true;
        Ok(())
    }
}

impl Drop for SessionCgroup {
    fn drop(&mut self) {
        if !self.owns_path {
            return;
        }
        if self.capabilities_verified {
            let _ = self.remove_if_empty();
        } else {
            let _ = self.remove_created_directory();
        }
    }
}

pub(super) trait CgroupOwnership {
    fn move_to_session(&mut self, pid: libc::pid_t) -> io::Result<()>;
    fn session_contains(&self, pid: libc::pid_t) -> io::Result<bool>;
    fn move_to_parent(&mut self, pid: libc::pid_t) -> io::Result<()>;
    fn parent_contains(&self, pid: libc::pid_t) -> io::Result<bool>;
    fn populated(&self) -> io::Result<bool>;
    fn remove_session(&mut self) -> io::Result<()>;
    fn cleanup_after_child_exit(&mut self) -> io::Result<()>;
    fn process_ids(&self) -> io::Result<Vec<libc::pid_t>>;
    fn kill(&self) -> io::Result<()>;
}

impl CgroupOwnership for SessionCgroup {
    fn move_to_session(&mut self, pid: libc::pid_t) -> io::Result<()> {
        validate_pid(pid)?;
        write_cgroup_file(&self.path.join(CGROUP_PROCS), &format!("{pid}\n"))
    }

    fn session_contains(&self, pid: libc::pid_t) -> io::Result<bool> {
        Ok(read_process_ids(&self.path.join(CGROUP_PROCS))?.contains(&pid))
    }

    fn move_to_parent(&mut self, pid: libc::pid_t) -> io::Result<()> {
        validate_pid(pid)?;
        write_cgroup_file(&self.parent_procs, &format!("{pid}\n"))
    }

    fn parent_contains(&self, pid: libc::pid_t) -> io::Result<bool> {
        Ok(read_process_ids(&self.parent_procs)?.contains(&pid))
    }

    fn populated(&self) -> io::Result<bool> {
        SessionCgroup::populated(self)
    }

    fn remove_session(&mut self) -> io::Result<()> {
        self.remove_if_empty()
    }

    fn cleanup_after_child_exit(&mut self) -> io::Result<()> {
        if self.removed {
            return Ok(());
        }
        match self.remove_if_empty() {
            Ok(()) => Ok(()),
            Err(primary) => match self.remove_created_directory() {
                Ok(()) => Ok(()),
                Err(cleanup) => Err(combined_error(
                    primary,
                    "post-reap cgroup cleanup failed",
                    cleanup,
                )),
            },
        }
    }

    fn process_ids(&self) -> io::Result<Vec<libc::pid_t>> {
        SessionCgroup::process_ids(self)
    }

    fn kill(&self) -> io::Result<()> {
        SessionCgroup::kill(self)
    }
}

fn validate_pid(pid: libc::pid_t) -> io::Result<()> {
    if pid <= 0 {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "PID must be positive"));
    }
    Ok(())
}

enum CgroupSetup<G> {
    Attached(G),
    CleanFailure(io::Error),
    DirtyFailure {
        error: io::Error,
        cgroup: G,
    },
}

fn attach_with_rollback<G: CgroupOwnership>(
    mut cgroup: G,
    pid: libc::pid_t,
) -> CgroupSetup<G> {
    let primary = match cgroup.move_to_session(pid) {
        Err(error) => error,
        Ok(()) => match cgroup.session_contains(pid) {
            Ok(true) => return CgroupSetup::Attached(cgroup),
            Ok(false) => io::Error::other("session cgroup did not confirm the attached child PID"),
            Err(error) => error,
        },
    };
    let rollback = rollback_attachment(&mut cgroup, pid);
    match rollback {
        Ok(()) => CgroupSetup::CleanFailure(primary),
        Err(error) => CgroupSetup::DirtyFailure {
            error: combined_error(primary, "cgroup attachment rollback failed", error),
            cgroup,
        },
    }
}

fn rollback_attachment<G: CgroupOwnership>(
    cgroup: &mut G,
    pid: libc::pid_t,
) -> io::Result<()> {
    cgroup.move_to_parent(pid)?;
    if !cgroup.parent_contains(pid)? {
        return Err(io::Error::other(
            "parent cgroup did not confirm the restored child PID",
        ));
    }
    if cgroup.populated()? {
        return Err(io::Error::other(
            "session cgroup remained populated after child rollback",
        ));
    }
    cgroup.remove_session()
}

fn combined_error(primary: io::Error, phase: &str, secondary: io::Error) -> io::Error {
    io::Error::new(primary.kind(), format!("{primary}; {phase}: {secondary}"))
}

trait HeldChildControl {
    type Error;

    fn pid(&self) -> libc::pid_t;
    fn release_held(&mut self) -> Result<(), Self::Error>;
    fn abort_held(&mut self) -> Result<(), Self::Error>;
}

pub(super) enum SelectedBackend<G> {
    Cgroup {
        registry: ProcessRegistry,
        cgroup: G,
    },
    Fallback {
        registry: ProcessRegistry,
    },
}

pub(super) type ProcessTreeBackend = SelectedBackend<SessionCgroup>;

pub(super) type ActiveProcessTreeBackend = ActiveProcessTree<SessionCgroup>;

fn create_attached_session_cgroup(pid: libc::pid_t) -> CgroupSetup<SessionCgroup> {
    let location = match CgroupLocation::discover_system() {
        Ok(location) => location,
        Err(error) => return CgroupSetup::CleanFailure(error),
    };
    let cgroup = match SessionCgroup::create(&location) {
        Ok(cgroup) => cgroup,
        Err(CgroupCreationError::Clean(error)) => return CgroupSetup::CleanFailure(error),
        Err(CgroupCreationError::Dirty { error, cgroup }) => {
            return CgroupSetup::DirtyFailure { error, cgroup };
        }
    };
    attach_with_rollback(cgroup, pid)
}

pub(super) fn initialize_held_child<D, T, W, P, F>(
    held: super::HeldChild,
    append_warning: W,
    prepare_tracker: P,
    finish_tracker: F,
) -> Result<(super::HeldChild, T), crate::host::HostError>
where
    W: FnMut(&str) -> Result<(), crate::host::HostError>,
    P: FnOnce(
        &super::HeldChild,
        &ProcessTreeBackend,
    ) -> Result<D, crate::host::HostError>,
    F: FnOnce(D, ProcessTreeBackend) -> T,
{
    let mut kernel = SystemLinuxKernel;
    configure_held_child(
        held,
        &mut kernel,
        create_attached_session_cgroup,
        append_warning,
        prepare_tracker,
        finish_tracker,
    )
}

impl HeldChildControl for super::HeldChild {
    type Error = crate::host::HostError;

    fn pid(&self) -> libc::pid_t {
        self.pid()
    }

    fn release_held(&mut self) -> Result<(), Self::Error> {
        self.release()
    }

    fn abort_held(&mut self) -> Result<(), Self::Error> {
        self.abort()
    }
}

fn configure_held_child<H, K, C, W, P, F, G, D, T, E>(
    mut held: H,
    kernel: &mut K,
    create_attached_cgroup: C,
    mut append_warning: W,
    prepare_tracker: P,
    finish_tracker: F,
) -> Result<(H, T), E>
where
    H: HeldChildControl,
    K: LinuxKernel,
    C: FnOnce(libc::pid_t) -> CgroupSetup<G>,
    G: CgroupOwnership,
    W: FnMut(&str) -> Result<(), E>,
    P: FnOnce(&H, &SelectedBackend<G>) -> Result<D, E>,
    F: FnOnce(D, SelectedBackend<G>) -> T,
    H::Error: std::fmt::Display,
    E: From<io::Error> + From<H::Error> + std::fmt::Display,
{
    let pid = held.pid();
    let mut registry = ProcessRegistry::new();
    if let Err(error) = registry.track(kernel, pid) {
        let primary = E::from(error);
        return Err(abort_startup(&mut held, primary, || None));
    }
    let mut backend = match create_attached_cgroup(pid) {
        CgroupSetup::Attached(cgroup) => SelectedBackend::Cgroup { registry, cgroup },
        CgroupSetup::CleanFailure(error) => {
            if let Err(error) = append_warning(&bounded_fallback_reason(&error)) {
                return Err(abort_startup(&mut held, error, || None));
            }
            SelectedBackend::Fallback { registry }
        }
        CgroupSetup::DirtyFailure { error, mut cgroup } => {
            let primary = E::from(error);
            return Err(abort_startup(&mut held, primary, || {
                cgroup.cleanup_after_child_exit().err()
            }));
        }
    };
    let prepared = match prepare_tracker(&held, &backend) {
        Ok(prepared) => prepared,
        Err(error) => {
            return Err(abort_startup(&mut held, error, || {
                cleanup_backend_after_abort(&mut backend)
            }));
        }
    };
    if let Err(error) = held.release_held() {
        let primary = E::from(error);
        return Err(abort_startup(&mut held, primary, || {
            cleanup_backend_after_abort(&mut backend)
        }));
    }
    let tracker = finish_tracker(prepared, backend);
    Ok((held, tracker))
}

fn abort_startup<H, E, C>(held: &mut H, primary: E, cleanup: C) -> E
where
    H: HeldChildControl,
    H::Error: std::fmt::Display,
    E: From<io::Error> + std::fmt::Display,
    C: FnOnce() -> Option<io::Error>,
{
    let abort_error = held.abort_held().err().map(|error| error.to_string());
    let cleanup_error = cleanup();
    compose_startup_error(primary, abort_error, cleanup_error)
}

fn cleanup_backend_after_abort<G: CgroupOwnership>(
    backend: &mut SelectedBackend<G>,
) -> Option<io::Error> {
    match backend {
        SelectedBackend::Cgroup { cgroup, .. } => cgroup.cleanup_after_child_exit().err(),
        SelectedBackend::Fallback { .. } => None,
    }
}

fn compose_startup_error<E>(
    primary: E,
    abort_error: Option<String>,
    cleanup_error: Option<io::Error>,
) -> E
where
    E: From<io::Error> + std::fmt::Display,
{
    if abort_error.is_none() && cleanup_error.is_none() {
        return primary;
    }
    let mut diagnostic = primary.to_string();
    if let Some(error) = abort_error {
        diagnostic.push_str("; held child abort failed: ");
        diagnostic.push_str(&error);
    }
    if let Some(error) = cleanup_error {
        diagnostic.push_str("; post-abort cgroup cleanup failed: ");
        diagnostic.push_str(&error.to_string());
    }
    E::from(io::Error::other(diagnostic))
}

fn bounded_fallback_reason(error: &io::Error) -> String {
    let reason = format!("cgroup v2 unavailable; using pidfd/subreaper fallback: {error}");
    let mut end = reason.len().min(4096);
    while !reason.is_char_boundary(end) {
        end -= 1;
    }
    reason[..end].to_owned()
}

fn validate_cgroup_location(location: &CgroupLocation) -> io::Result<()> {
    reject_symlink(&location.mount_point)?;
    let relative = location
        .current
        .strip_prefix(&location.mount_point)
        .map_err(|_| io::Error::other("current cgroup is outside cgroup2 mount"))?;
    let mut path = location.mount_point.clone();
    for component in relative.components() {
        let Component::Normal(component) = component else {
            return Err(io::Error::other("unsafe current cgroup path"));
        };
        path.push(component);
        reject_symlink(&path)?;
    }
    Ok(())
}

fn validate_child_name(name: &str) -> io::Result<()> {
    let path = Path::new(name);
    if path.components().count() != 1
        || !matches!(path.components().next(), Some(Component::Normal(_)))
    {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "unsafe session cgroup name",
        ));
    }
    Ok(())
}

fn reject_symlink(path: &Path) -> io::Result<()> {
    let metadata = fs::symlink_metadata(path)?;
    if metadata.file_type().is_symlink() {
        return Err(io::Error::other(format!(
            "refusing cgroup symlink {}",
            path.display()
        )));
    }
    if !metadata.is_dir() {
        return Err(io::Error::other(format!(
            "cgroup path is not a directory: {}",
            path.display()
        )));
    }
    Ok(())
}

fn require_cgroup_file(path: &Path, readable: bool) -> io::Result<()> {
    let metadata = fs::symlink_metadata(path).map_err(|error| {
        io::Error::new(error.kind(), format!("required {} is unavailable: {error}", path.display()))
    })?;
    if metadata.file_type().is_symlink() || !metadata.is_file() {
        return Err(io::Error::other(format!(
            "required cgroup file is unsafe: {}",
            path.display()
        )));
    }
    let mut options = OpenOptions::new();
    if readable {
        options.read(true);
    } else {
        options.write(true);
    }
    options.open(path).map(|_| ()).map_err(|error| {
        io::Error::new(error.kind(), format!("required {} is unusable: {error}", path.display()))
    })
}

fn write_cgroup_file(path: &Path, value: &str) -> io::Result<()> {
    let mut file = OpenOptions::new().write(true).open(path)?;
    file.write_all(value.as_bytes())
}

fn collect_process_ids(path: &Path, processes: &mut Vec<libc::pid_t>) -> io::Result<()> {
    reject_symlink(path)?;
    processes.extend(read_process_ids(&path.join(CGROUP_PROCS))?);
    for entry in fs::read_dir(path)? {
        let entry = entry?;
        let metadata = fs::symlink_metadata(entry.path())?;
        if metadata.file_type().is_symlink() {
            return Err(io::Error::other(format!(
                "refusing cgroup symlink {}",
                entry.path().display()
            )));
        }
        if metadata.is_dir() {
            collect_process_ids(&entry.path(), processes)?;
        } else if !metadata.is_file() {
            return Err(io::Error::other(format!(
                "unsafe cgroup entry {}",
                entry.path().display()
            )));
        }
    }
    Ok(())
}

fn read_process_ids(path: &Path) -> io::Result<Vec<libc::pid_t>> {
    let mut contents = String::new();
    OpenOptions::new().read(true).open(path)?.read_to_string(&mut contents)?;
    let mut processes = Vec::new();
    for line in contents.lines() {
        let pid = line
            .parse::<libc::pid_t>()
            .map_err(|_| io::Error::other("cgroup.procs contains an invalid positive PID"))?;
        if pid <= 0 {
            return Err(io::Error::other("cgroup.procs contains an invalid positive PID"));
        }
        processes.push(pid);
    }
    Ok(processes)
}

fn remove_empty_cgroup_tree(path: &Path) -> io::Result<()> {
    reject_symlink(path)?;
    let mut children = Vec::new();
    for entry in fs::read_dir(path)? {
        let entry = entry?;
        let metadata = fs::symlink_metadata(entry.path())?;
        if metadata.file_type().is_symlink() {
            return Err(io::Error::other("refusing symlink during cgroup cleanup"));
        }
        if metadata.is_dir() {
            children.push(entry.path());
        } else if !metadata.is_file() {
            return Err(io::Error::other("unsafe entry during cgroup cleanup"));
        }
    }
    for child in children {
        remove_empty_cgroup_tree(&child)?;
    }
    fs::remove_dir(path)
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum SignalResult {
    Delivered,
    Exited,
}

pub(super) struct ProcessRegistry {
    processes: HashMap<libc::pid_t, (u64, OwnedFd)>,
    next_token: u64,
}

impl ProcessRegistry {
    fn new() -> Self {
        Self {
            processes: HashMap::new(),
            next_token: 1,
        }
    }

    fn track<K: LinuxKernel>(&mut self, kernel: &mut K, pid: libc::pid_t) -> io::Result<()> {
        self.insert(pid, kernel.open_pidfd(pid)?)
    }

    fn insert(&mut self, pid: libc::pid_t, pidfd: OwnedFd) -> io::Result<()> {
        let token = self.next_token;
        self.next_token = token.checked_add(1)
            .ok_or_else(|| io::Error::other("process token space exhausted"))?;
        self.processes.insert(pid, (token, pidfd));
        Ok(())
    }

    #[cfg(test)]
    fn process(&self, pid: libc::pid_t) -> Option<&OwnedFd> {
        self.processes.get(&pid).map(|(_, pidfd)| pidfd)
    }

    fn remove_pid(&mut self, pid: libc::pid_t) {
        self.processes.remove(&pid);
    }

    fn send_signal<K: LinuxKernel>(
        &mut self,
        kernel: &mut K,
        pid: libc::pid_t,
        signal: libc::c_int,
    ) -> io::Result<SignalResult> {
        let (_, pidfd) = self
            .processes
            .get(&pid)
            .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, format!("untracked PID {pid}")))?;
        match kernel.send_pidfd_signal(pidfd.as_fd(), signal) {
            Ok(()) => Ok(SignalResult::Delivered),
            Err(error) if error.raw_os_error() == Some(libc::ESRCH) => {
                self.processes.remove(&pid);
                Ok(SignalResult::Exited)
            }
            Err(error) => Err(error),
        }
    }

    fn remove_exited<K: LinuxKernel>(&mut self, kernel: &mut K) -> io::Result<()> {
        let mut exited = Vec::new();
        for (&pid, (_, pidfd)) in &self.processes {
            if kernel.pidfd_is_ready(pidfd.as_fd())? {
                exited.push(pid);
            }
        }
        for pid in exited {
            self.processes.remove(&pid);
        }
        Ok(())
    }

    fn contains_pid(&self, pid: libc::pid_t) -> bool {
        self.processes.contains_key(&pid)
    }

    fn signal_all<K: LinuxKernel>(
        &mut self,
        kernel: &mut K,
        signal: libc::c_int,
    ) -> super::DescendantDelivery {
        let mut delivery = super::DescendantDelivery::default();
        for pid in self.pids() {
            delivery.attempted += 1;
            match self.send_signal(kernel, pid, signal) {
                Ok(_) => delivery.succeeded += 1,
                Err(error) => delivery.failures.push(format!("PID {pid}: {error}")),
            }
        }
        delivery
    }

    fn pids(&self) -> Vec<libc::pid_t> {
        self.processes.keys().copied().collect()
    }

    fn is_empty(&self) -> bool {
        self.processes.is_empty()
    }

    fn snapshot(&self, root: Option<libc::pid_t>) -> Vec<crate::protocol::ListedProcess> {
        let mut processes = Vec::with_capacity(self.processes.len());
        for (&pid, (token, _)) in &self.processes {
            processes.push(crate::protocol::ListedProcess {
                token: *token,
                pid: pid as u64,
                original_root: Some(pid) == root,
            });
        }
        processes.sort_unstable_by_key(|process| process.token);
        processes
    }

    fn signal_token<K: LinuxKernel>(
        &mut self, kernel: &mut K, token: u64, signal: libc::c_int,
    ) -> io::Result<()> {
        self.remove_exited(kernel)?;
        let pid = self.processes.iter()
            .find_map(|(pid, (candidate, _))| (*candidate == token).then_some(*pid))
            .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "unknown or exited process token"))?;
        match self.send_signal(kernel, pid, signal)? {
            SignalResult::Delivered => Ok(()),
            SignalResult::Exited => Err(io::Error::new(io::ErrorKind::NotFound, "process exited")),
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct LinuxProcess {
    parent: libc::pid_t,
    session: libc::pid_t,
    process_group: libc::pid_t,
    start: u128,
    holds_pty: bool,
}

trait ProcessSource {
    fn snapshot(
        &mut self,
        pty: super::PtySlaveIdentity,
    ) -> io::Result<HashMap<libc::pid_t, LinuxProcess>>;

    fn process(
        &mut self,
        pid: libc::pid_t,
        pty: super::PtySlaveIdentity,
    ) -> io::Result<Option<LinuxProcess>>;
}

struct ProcProcessSource;

impl ProcessSource for ProcProcessSource {
    fn snapshot(
        &mut self,
        pty: super::PtySlaveIdentity,
    ) -> io::Result<HashMap<libc::pid_t, LinuxProcess>> {
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
            // An unreadable unrelated process is not ownership evidence. Known
            // processes are checked explicitly after this discovery pass.
            if let Ok(Some(process)) = read_linux_process(&entry.path(), pty) {
                processes.insert(pid, process);
            }
        }
        Ok(processes)
    }

    fn process(
        &mut self,
        pid: libc::pid_t,
        pty: super::PtySlaveIdentity,
    ) -> io::Result<Option<LinuxProcess>> {
        read_linux_process(&Path::new("/proc").join(pid.to_string()), pty)
    }
}

fn read_linux_process(path: &Path, pty: super::PtySlaveIdentity) -> io::Result<Option<LinuxProcess>> {
    let stat = match fs::read_to_string(path.join("stat")) {
        Ok(stat) => stat,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error),
    };
    let invalid = || io::Error::new(io::ErrorKind::InvalidData, "invalid Linux process stat");
    let fields = stat.rsplit_once(") ").ok_or_else(invalid)?.1
        .split_ascii_whitespace().collect::<Vec<_>>();
    if fields.len() < 20 {
        return Err(invalid());
    }
    let parent = fields[1].parse::<libc::pid_t>().map_err(|_| invalid())?;
    let process_group = fields[2].parse::<libc::pid_t>().map_err(|_| invalid())?;
    let session = fields[3].parse::<libc::pid_t>().map_err(|_| invalid())?;
    let start = fields[19].parse::<u128>().map_err(|_| invalid())?;
    // Descriptor access is only supplementary ownership evidence. Existing pidfds,
    // parent relationships, and subreaper adoption remain authoritative.
    let holds_pty = fs::read_dir(path.join("fd"))
        .into_iter()
        .flatten()
        .filter_map(Result::ok)
        .filter_map(|fd| fs::metadata(fd.path()).ok())
        .any(|metadata| {
            metadata.rdev() as libc::dev_t == pty.device
                && metadata.ino() as libc::ino_t == pty.inode
        });
    Ok(Some(LinuxProcess { parent, session, process_group, start, holds_pty }))
}

pub(super) struct ActiveProcessTree<G> {
    backend: SelectedBackend<G>,
    root: libc::pid_t,
    pty: super::PtySlaveIdentity,
    fallback_live: HashMap<libc::pid_t, u128>,
    fallback_empty_observations: usize,
    root_reaped: bool,
    cleaned: bool,
}

impl<G: CgroupOwnership> ActiveProcessTree<G> {
    pub(super) fn activate(
        backend: SelectedBackend<G>,
        root: libc::pid_t,
        root_start: u128,
        pty: super::PtySlaveIdentity,
    ) -> Self {
        Self {
            backend,
            root,
            pty,
            fallback_live: HashMap::from([(root, root_start)]),
            fallback_empty_observations: 0,
            root_reaped: false,
            cleaned: false,
        }
    }

    pub(super) fn mark_root_reaped(&mut self) {
        self.root_reaped = true;
        self.fallback_live.remove(&self.root);
        match &mut self.backend {
            SelectedBackend::Cgroup { registry, .. }
            | SelectedBackend::Fallback { registry } => registry.remove_pid(self.root),
        }
    }

    fn signal_with<K: LinuxKernel, S: ProcessSource>(
        &mut self,
        kernel: &mut K,
        source: &mut S,
        signal: libc::c_int,
    ) -> io::Result<super::DescendantDelivery> {
        if signal == libc::SIGKILL
            && let SelectedBackend::Cgroup { cgroup, .. } = &self.backend
        {
            let mut delivery = super::DescendantDelivery::default();
            delivery.attempted = 1;
            match cgroup.kill() {
                Ok(()) => delivery.succeeded = 1,
                Err(error) => delivery.failures.push(format!("cgroup.kill: {error}")),
            }
            return Ok(delivery);
        }
        self.refresh_with(kernel, source)?;
        let registry = match &mut self.backend {
            SelectedBackend::Cgroup { registry, .. }
            | SelectedBackend::Fallback { registry } => registry,
        };
        Ok(registry.signal_all(kernel, signal))
    }

    fn signal_foreground_with<K: LinuxKernel, S: ProcessSource>(
        &mut self,
        kernel: &mut K,
        source: &mut S,
        process_group: libc::pid_t,
        signal: libc::c_int,
    ) -> io::Result<()> {
        self.refresh_with(kernel, source)?;
        let pids = match &self.backend {
            SelectedBackend::Cgroup { registry, .. }
            | SelectedBackend::Fallback { registry } => registry.pids(),
        };
        let mut matching = Vec::new();
        for pid in pids {
            if source
                .process(pid, self.pty)?
                .is_some_and(|process| process.process_group == process_group)
            {
                matching.push(pid);
            }
        }
        let registry = match &mut self.backend {
            SelectedBackend::Cgroup { registry, .. }
            | SelectedBackend::Fallback { registry } => registry,
        };
        for pid in matching {
            registry.send_signal(kernel, pid, signal)?;
        }
        Ok(())
    }

    fn refresh_with<K: LinuxKernel, S: ProcessSource>(
        &mut self,
        kernel: &mut K,
        source: &mut S,
    ) -> io::Result<()> {
        match &mut self.backend {
            SelectedBackend::Cgroup { registry, cgroup } => {
                refresh_cgroup_registry(registry, cgroup, kernel)?;
            }
            SelectedBackend::Fallback { registry } => refresh_fallback_registry(
                registry,
                &mut self.fallback_live,
                kernel,
                source,
                self.root,
                self.pty,
            )?,
        }
        Ok(())
    }

    fn is_live_with<K: LinuxKernel, S: ProcessSource>(
        &mut self,
        kernel: &mut K,
        source: &mut S,
    ) -> io::Result<bool> {
        if !self.root_reaped {
            return Ok(true);
        }
        let backend_empty = match &mut self.backend {
            SelectedBackend::Cgroup { registry, cgroup } => {
                registry.remove_exited(kernel)?;
                !cgroup.populated()?
            }
            SelectedBackend::Fallback { registry } => {
                refresh_fallback_registry(
                    registry,
                    &mut self.fallback_live,
                    kernel,
                    source,
                    self.root,
                    self.pty,
                )?;
                let empty = registry.is_empty() && self.fallback_live.is_empty();
                if empty {
                    self.fallback_empty_observations += 1;
                } else {
                    self.fallback_empty_observations = 0;
                }
                empty && self.fallback_empty_observations >= FALLBACK_EMPTY_CONFIRMATIONS
            }
        };
        let no_children = drain_adopted_children(kernel, &mut self.backend, &mut self.fallback_live)?;
        if !backend_empty || !no_children {
            return Ok(true);
        }
        if !self.cleaned {
            if let SelectedBackend::Cgroup { cgroup, .. } = &mut self.backend {
                cgroup.remove_session()?;
            }
            self.cleaned = true;
        }
        Ok(false)
    }
}

pub(super) fn process_start(
    pid: libc::pid_t,
    pty: super::PtySlaveIdentity,
) -> io::Result<u128> {
    ProcProcessSource
        .process(pid, pty)?
        .map(|process| process.start)
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "PTY child disappeared"))
}

impl ActiveProcessTree<SessionCgroup> {
    pub(super) fn list_processes(&mut self) -> io::Result<Vec<crate::protocol::ListedProcess>> {
        self.refresh_with(&mut SystemLinuxKernel, &mut ProcProcessSource)?;
        let registry = match &self.backend {
            SelectedBackend::Cgroup { registry, .. } | SelectedBackend::Fallback { registry } => registry,
        };
        Ok(registry.snapshot((!self.root_reaped).then_some(self.root)))
    }

    pub(super) fn signal_process(&mut self, token: u64, signal: libc::c_int) -> io::Result<()> {
        self.refresh_with(&mut SystemLinuxKernel, &mut ProcProcessSource)?;
        let registry = match &mut self.backend {
            SelectedBackend::Cgroup { registry, .. } | SelectedBackend::Fallback { registry } => registry,
        };
        registry.signal_token(&mut SystemLinuxKernel, token, signal)
    }

    pub(super) fn signal(&mut self, signal: libc::c_int) -> io::Result<super::DescendantDelivery> {
        self.signal_with(&mut SystemLinuxKernel, &mut ProcProcessSource, signal)
    }

    pub(super) fn signal_foreground(
        &mut self,
        process_group: libc::pid_t,
        signal: libc::c_int,
    ) -> io::Result<()> {
        self.signal_foreground_with(
            &mut SystemLinuxKernel,
            &mut ProcProcessSource,
            process_group,
            signal,
        )
    }

    pub(super) fn is_live(&mut self) -> io::Result<bool> {
        self.is_live_with(&mut SystemLinuxKernel, &mut ProcProcessSource)
    }
}

fn refresh_cgroup_registry<K: LinuxKernel, G: CgroupOwnership>(
    registry: &mut ProcessRegistry,
    cgroup: &G,
    kernel: &mut K,
) -> io::Result<()> {
    registry.remove_exited(kernel)?;
    let processes = cgroup.process_ids()?;
    registry.processes.retain(|pid, _| processes.contains(pid));
    for pid in processes {
        if registry.contains_pid(pid) {
            continue;
        }
        let pidfd = match kernel.open_pidfd(pid) {
            Ok(pidfd) => pidfd,
            Err(error) if error.raw_os_error() == Some(libc::ESRCH) => continue,
            Err(error) => return Err(error),
        };
        if cgroup.process_ids()?.contains(&pid) && !kernel.pidfd_is_ready(pidfd.as_fd())? {
            registry.insert(pid, pidfd)?;
        }
    }
    Ok(())
}

fn refresh_fallback_registry<K: LinuxKernel, S: ProcessSource>(
    registry: &mut ProcessRegistry,
    live: &mut HashMap<libc::pid_t, u128>,
    kernel: &mut K,
    source: &mut S,
    root: libc::pid_t,
    pty: super::PtySlaveIdentity,
) -> io::Result<()> {
    registry.remove_exited(kernel)?;
    let mut processes = source.snapshot(pty)?;
    for pid in registry.pids() {
        if !processes.contains_key(&pid) {
            if let Some(process) = source.process(pid, pty)? {
                processes.insert(pid, process);
            }
        }
    }
    live.retain(|pid, start| {
        registry.contains_pid(*pid)
            && processes.get(pid).is_none_or(|process| process.start == *start)
    });
    let host_pid = std::process::id() as libc::pid_t;
    loop {
        let mut changed = false;
        for (pid, candidate) in &processes {
            if live.contains_key(pid)
                || registry.contains_pid(*pid)
                || !owned_by_fallback(candidate, root, host_pid, live)
            {
                continue;
            }
            let pidfd = match kernel.open_pidfd(*pid) {
                Ok(pidfd) => pidfd,
                Err(error) if error.raw_os_error() == Some(libc::ESRCH) => continue,
                Err(error) => return Err(error),
            };
            let valid = source
                .process(*pid, pty)?
                .is_some_and(|current| {
                    current == *candidate && owned_by_fallback(&current, root, host_pid, live)
                });
            if valid && !kernel.pidfd_is_ready(pidfd.as_fd())? {
                registry.insert(*pid, pidfd)?;
                live.insert(*pid, candidate.start);
                changed = true;
            }
        }
        if !changed {
            break;
        }
    }
    Ok(())
}

fn owned_by_fallback(
    process: &LinuxProcess,
    root: libc::pid_t,
    host_pid: libc::pid_t,
    live: &HashMap<libc::pid_t, u128>,
) -> bool {
    process.parent == host_pid
        || (process.session == root && live.contains_key(&root))
        || process.holds_pty
        || live.contains_key(&process.parent)
}

fn drain_adopted_children<K: LinuxKernel, G>(
    kernel: &mut K,
    backend: &mut SelectedBackend<G>,
    live: &mut HashMap<libc::pid_t, u128>,
) -> io::Result<bool> {
    loop {
        match kernel.reap_nonblocking()? {
            ReapResult::Reaped(pid) => {
                live.remove(&pid);
                match backend {
                    SelectedBackend::Cgroup { registry, .. }
                    | SelectedBackend::Fallback { registry } => registry.remove_pid(pid),
                }
            }
            ReapResult::Running => return Ok(false),
            ReapResult::NoChildren => return Ok(true),
        }
    }
}

#[cfg(test)]
mod tests {
    use std::cell::RefCell;
    use std::collections::{HashMap, HashSet};
    use std::fs::{self, File};
    use std::io;
    use std::os::fd::{AsRawFd, BorrowedFd, OwnedFd};
    use std::path::{Path, PathBuf};
    use std::rc::Rc;
    use std::sync::atomic::{AtomicU64, Ordering};

    use super::{
        ActiveProcessTree, CgroupCreationError, CgroupLocation, CgroupOwnership, CgroupSetup,
        HeldChildControl, LinuxKernel, LinuxProcess, ProcessRegistry, ProcessSource, ReapResult,
        SelectedBackend, SessionCgroup, SignalResult, attach_with_rollback, configure_held_child,
        verify_subreaper,
    };

    static NEXT_DIRECTORY: AtomicU64 = AtomicU64::new(0);

    struct DirectoryGuard(PathBuf);

    impl DirectoryGuard {
        fn new(label: &str) -> Self {
            let sequence = NEXT_DIRECTORY.fetch_add(1, Ordering::Relaxed);
            let path = std::env::temp_dir().join(format!(
                "orion-linux-process-tree-{label}-{}-{sequence}",
                std::process::id()
            ));
            fs::create_dir(&path).unwrap();
            Self(path)
        }

        fn path(&self) -> &Path {
            &self.0
        }
    }

    impl Drop for DirectoryGuard {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    #[derive(Clone, Copy, Debug, Eq, PartialEq)]
    enum KernelCall {
        SetSubreaper,
        GetSubreaper,
    }

    struct FakeKernel {
        calls: Vec<KernelCall>,
        set_subreaper_error: Option<i32>,
        get_subreaper_error: Option<i32>,
        subreaper_after_set: bool,
        pidfd_open_error: Option<i32>,
        opened_pidfds: HashMap<libc::pid_t, Vec<i32>>,
        exited_pidfds: HashSet<i32>,
        exited_on_open: HashSet<libc::pid_t>,
        signalled_pidfds: Vec<i32>,
        signal_calls: Vec<(i32, libc::c_int)>,
        reap_results: Vec<ReapResult>,
        reaped_pids: Vec<libc::pid_t>,
    }

    #[derive(Clone, Debug, Eq, PartialEq)]
    enum StartupCall {
        ForkChildHeld,
        OpenRootPidfd,
        AttachToCgroup,
        CgroupFailed(&'static str),
        MoveToSession,
        ConfirmSession,
        MoveBackToParent,
        ConfirmParent,
        ConfirmSessionEmpty,
        RemoveSessionCgroup,
        AppendDurableHostWarning,
        PrepareTracker,
        ReleaseChildFailed,
        ReleaseChild,
        KillHeldChild,
        ReapHeldChild,
        CleanupAfterChildDeath,
    }

    struct OrderingKernel {
        calls: Rc<RefCell<Vec<StartupCall>>>,
        open_error: Option<i32>,
    }

    impl LinuxKernel for OrderingKernel {
        fn set_child_subreaper(&mut self) -> io::Result<()> {
            unreachable!()
        }

        fn child_subreaper(&mut self) -> io::Result<bool> {
            unreachable!()
        }

        fn open_pidfd(&mut self, _pid: libc::pid_t) -> io::Result<OwnedFd> {
            self.calls.borrow_mut().push(StartupCall::OpenRootPidfd);
            if let Some(error) = self.open_error {
                return Err(io::Error::from_raw_os_error(error));
            }
            Ok(OwnedFd::from(File::open("/dev/null")?))
        }

        fn send_pidfd_signal(
            &mut self,
            _pidfd: BorrowedFd<'_>,
            _signal: libc::c_int,
        ) -> io::Result<()> {
            unreachable!()
        }

        fn pidfd_is_ready(&mut self, _pidfd: BorrowedFd<'_>) -> io::Result<bool> {
            unreachable!()
        }

        fn reap_nonblocking(&mut self) -> io::Result<ReapResult> {
            unreachable!()
        }
    }

    struct FakeHeldChild {
        calls: Rc<RefCell<Vec<StartupCall>>>,
        ownership: Option<Rc<RefCell<OwnershipState>>>,
        abort_error: bool,
        release_error: bool,
        aborted: bool,
        released: bool,
    }

    impl FakeHeldChild {
        fn new(calls: Rc<RefCell<Vec<StartupCall>>>) -> Self {
            calls.borrow_mut().push(StartupCall::ForkChildHeld);
            Self {
                calls,
                ownership: None,
                abort_error: false,
                release_error: false,
                aborted: false,
                released: false,
            }
        }

        fn with_ownership(
            calls: Rc<RefCell<Vec<StartupCall>>>,
            ownership: Rc<RefCell<OwnershipState>>,
        ) -> Self {
            calls.borrow_mut().push(StartupCall::ForkChildHeld);
            Self {
                calls,
                ownership: Some(ownership),
                abort_error: false,
                release_error: false,
                aborted: false,
                released: false,
            }
        }

        fn failing_release(mut self) -> Self {
            self.release_error = true;
            self
        }

        fn failing_abort(mut self) -> Self {
            self.abort_error = true;
            self
        }
    }

    impl HeldChildControl for FakeHeldChild {
        type Error = io::Error;

        fn pid(&self) -> libc::pid_t {
            41
        }

        fn release_held(&mut self) -> io::Result<()> {
            if self.release_error {
                self.calls
                    .borrow_mut()
                    .push(StartupCall::ReleaseChildFailed);
                return Err(io::Error::other("held child release failed"));
            }
            self.calls.borrow_mut().push(StartupCall::ReleaseChild);
            self.released = true;
            Ok(())
        }

        fn abort_held(&mut self) -> io::Result<()> {
            self.calls.borrow_mut().push(StartupCall::KillHeldChild);
            self.calls.borrow_mut().push(StartupCall::ReapHeldChild);
            if let Some(ownership) = &self.ownership {
                ownership.borrow_mut().process = ProcessPlace::Dead;
            }
            self.aborted = true;
            if self.abort_error {
                return Err(io::Error::other("held child abort failed"));
            }
            Ok(())
        }
    }

    impl Drop for FakeHeldChild {
        fn drop(&mut self) {
            if !self.released && !self.aborted {
                self.calls.borrow_mut().push(StartupCall::KillHeldChild);
                self.calls.borrow_mut().push(StartupCall::ReapHeldChild);
                if let Some(ownership) = &self.ownership {
                    ownership.borrow_mut().process = ProcessPlace::Dead;
                }
            }
        }
    }

    #[derive(Clone, Copy, Debug, Eq, PartialEq)]
    enum ProcessPlace {
        Parent,
        Session,
        Dead,
    }

    struct OwnershipState {
        process: ProcessPlace,
        session_exists: bool,
        fail_session_confirmation: bool,
        fail_rollback: bool,
        fail_removal: bool,
        fail_cleanup_after_exit: bool,
    }

    struct StatefulCgroup {
        calls: Rc<RefCell<Vec<StartupCall>>>,
        state: Rc<RefCell<OwnershipState>>,
    }

    impl CgroupOwnership for StatefulCgroup {
        fn move_to_session(&mut self, _pid: libc::pid_t) -> io::Result<()> {
            self.calls.borrow_mut().push(StartupCall::MoveToSession);
            self.state.borrow_mut().process = ProcessPlace::Session;
            Ok(())
        }

        fn session_contains(&self, _pid: libc::pid_t) -> io::Result<bool> {
            self.calls.borrow_mut().push(StartupCall::ConfirmSession);
            let state = self.state.borrow();
            if state.fail_session_confirmation {
                return Err(io::Error::other("session confirmation failed"));
            }
            Ok(state.process == ProcessPlace::Session)
        }

        fn move_to_parent(&mut self, _pid: libc::pid_t) -> io::Result<()> {
            self.calls.borrow_mut().push(StartupCall::MoveBackToParent);
            let mut state = self.state.borrow_mut();
            if state.fail_rollback {
                return Err(io::Error::other("parent rollback failed"));
            }
            state.process = ProcessPlace::Parent;
            Ok(())
        }

        fn parent_contains(&self, _pid: libc::pid_t) -> io::Result<bool> {
            self.calls.borrow_mut().push(StartupCall::ConfirmParent);
            Ok(self.state.borrow().process == ProcessPlace::Parent)
        }

        fn populated(&self) -> io::Result<bool> {
            self.calls.borrow_mut().push(StartupCall::ConfirmSessionEmpty);
            Ok(self.state.borrow().process == ProcessPlace::Session)
        }

        fn remove_session(&mut self) -> io::Result<()> {
            self.calls
                .borrow_mut()
                .push(StartupCall::RemoveSessionCgroup);
            let mut state = self.state.borrow_mut();
            if state.fail_removal || state.process == ProcessPlace::Session {
                return Err(io::Error::other("session cgroup removal failed"));
            }
            state.session_exists = false;
            Ok(())
        }

        fn cleanup_after_child_exit(&mut self) -> io::Result<()> {
            self.calls
                .borrow_mut()
                .push(StartupCall::CleanupAfterChildDeath);
            let mut state = self.state.borrow_mut();
            if state.process != ProcessPlace::Dead {
                return Err(io::Error::other("child is still live"));
            }
            if state.fail_cleanup_after_exit {
                return Err(io::Error::other("post-reap cleanup failed"));
            }
            state.session_exists = false;
            Ok(())
        }

        fn process_ids(&self) -> io::Result<Vec<libc::pid_t>> {
            Ok(if self.state.borrow().process == ProcessPlace::Session {
                vec![41]
            } else {
                Vec::new()
            })
        }

        fn kill(&self) -> io::Result<()> {
            self.state.borrow_mut().process = ProcessPlace::Dead;
            Ok(())
        }
    }

    impl CgroupOwnership for () {
        fn move_to_session(&mut self, _pid: libc::pid_t) -> io::Result<()> {
            unreachable!()
        }

        fn session_contains(&self, _pid: libc::pid_t) -> io::Result<bool> {
            unreachable!()
        }

        fn move_to_parent(&mut self, _pid: libc::pid_t) -> io::Result<()> {
            unreachable!()
        }

        fn parent_contains(&self, _pid: libc::pid_t) -> io::Result<bool> {
            unreachable!()
        }

        fn populated(&self) -> io::Result<bool> {
            unreachable!()
        }

        fn remove_session(&mut self) -> io::Result<()> {
            unreachable!()
        }

        fn cleanup_after_child_exit(&mut self) -> io::Result<()> {
            unreachable!()
        }

        fn process_ids(&self) -> io::Result<Vec<libc::pid_t>> {
            unreachable!()
        }

        fn kill(&self) -> io::Result<()> {
            unreachable!()
        }
    }

    impl Default for FakeKernel {
        fn default() -> Self {
            Self {
                calls: Vec::new(),
                set_subreaper_error: None,
                get_subreaper_error: None,
                subreaper_after_set: false,
                pidfd_open_error: None,
                opened_pidfds: HashMap::new(),
                exited_pidfds: HashSet::new(),
                exited_on_open: HashSet::new(),
                signalled_pidfds: Vec::new(),
                signal_calls: Vec::new(),
                reap_results: Vec::new(),
                reaped_pids: Vec::new(),
            }
        }
    }

    impl FakeKernel {
        fn reuse_pid(&mut self, pid: libc::pid_t) {
            let replacement = self.open_pidfd(pid).unwrap();
            drop(replacement);
        }

        fn mark_exited(&mut self, pidfd: i32) {
            self.exited_pidfds.insert(pidfd);
        }

        fn signalled_pidfds(&self) -> Vec<i32> {
            self.signalled_pidfds.clone()
        }
    }

    impl LinuxKernel for FakeKernel {
        fn set_child_subreaper(&mut self) -> io::Result<()> {
            self.calls.push(KernelCall::SetSubreaper);
            match self.set_subreaper_error {
                Some(error) => Err(io::Error::from_raw_os_error(error)),
                None => Ok(()),
            }
        }

        fn child_subreaper(&mut self) -> io::Result<bool> {
            self.calls.push(KernelCall::GetSubreaper);
            match self.get_subreaper_error {
                Some(error) => Err(io::Error::from_raw_os_error(error)),
                None => Ok(self.subreaper_after_set),
            }
        }

        fn open_pidfd(&mut self, pid: libc::pid_t) -> io::Result<OwnedFd> {
            if let Some(error) = self.pidfd_open_error {
                return Err(io::Error::from_raw_os_error(error));
            }
            let pidfd = OwnedFd::from(File::open("/dev/null")?);
            if self.exited_on_open.contains(&pid) {
                self.exited_pidfds.insert(pidfd.as_raw_fd());
            }
            self.opened_pidfds
                .entry(pid)
                .or_default()
                .push(pidfd.as_raw_fd());
            Ok(pidfd)
        }

        fn send_pidfd_signal(
            &mut self,
            pidfd: BorrowedFd<'_>,
            signal: libc::c_int,
        ) -> io::Result<()> {
            let raw_fd = pidfd.as_raw_fd();
            self.signalled_pidfds.push(raw_fd);
            self.signal_calls.push((raw_fd, signal));
            if self.exited_pidfds.contains(&raw_fd) {
                return Err(io::Error::from_raw_os_error(libc::ESRCH));
            }
            Ok(())
        }

        fn pidfd_is_ready(&mut self, pidfd: BorrowedFd<'_>) -> io::Result<bool> {
            Ok(self.exited_pidfds.contains(&pidfd.as_raw_fd()))
        }

        fn reap_nonblocking(&mut self) -> io::Result<ReapResult> {
            let result = if self.reap_results.len() > 1 {
                self.reap_results.remove(0)
            } else {
                self.reap_results
                    .first()
                    .copied()
                    .unwrap_or(ReapResult::NoChildren)
            };
            if let ReapResult::Reaped(pid) = result {
                self.reaped_pids.push(pid);
            }
            Ok(result)
        }
    }

    #[test]
    fn verifies_subreaper_after_setting_it() {
        let mut kernel = FakeKernel {
            subreaper_after_set: true,
            ..FakeKernel::default()
        };

        verify_subreaper(&mut kernel).unwrap();

        assert_eq!(
            kernel.calls,
            vec![KernelCall::SetSubreaper, KernelCall::GetSubreaper]
        );
    }

    #[test]
    fn stops_when_setting_subreaper_fails() {
        let mut kernel = FakeKernel {
            set_subreaper_error: Some(libc::EPERM),
            ..FakeKernel::default()
        };

        let error = verify_subreaper(&mut kernel).unwrap_err();

        assert_eq!(error.raw_os_error(), Some(libc::EPERM));
        assert_eq!(kernel.calls, vec![KernelCall::SetSubreaper]);
    }

    #[test]
    fn reports_failed_subreaper_verification() {
        let mut kernel = FakeKernel {
            get_subreaper_error: Some(libc::EINVAL),
            ..FakeKernel::default()
        };

        let error = verify_subreaper(&mut kernel).unwrap_err();

        assert_eq!(error.raw_os_error(), Some(libc::EINVAL));
        assert_eq!(
            kernel.calls,
            vec![KernelCall::SetSubreaper, KernelCall::GetSubreaper]
        );
    }

    #[test]
    fn rejects_false_subreaper_verification() {
        let mut kernel = FakeKernel::default();

        let error = verify_subreaper(&mut kernel).unwrap_err();

        assert_eq!(error.kind(), io::ErrorKind::Other);
        assert_eq!(
            kernel.calls,
            vec![KernelCall::SetSubreaper, KernelCall::GetSubreaper]
        );
    }

    #[test]
    fn process_tokens_retire_on_exit_and_never_resolve_to_reused_pids() {
        let mut kernel = FakeKernel::default();
        let mut registry = ProcessRegistry::new();
        registry.track(&mut kernel, 41).unwrap();
        let token = registry.snapshot(Some(41))[0].token;
        let pidfd = registry.process(41).unwrap().as_raw_fd();
        kernel.reuse_pid(41);
        registry.signal_token(&mut kernel, token, libc::SIGCONT).unwrap();
        assert_eq!(kernel.signalled_pidfds(), vec![pidfd]);
        kernel.mark_exited(pidfd);
        assert!(registry.signal_token(&mut kernel, token, libc::SIGKILL).is_err());
        registry.track(&mut kernel, 41).unwrap();
        kernel.exited_pidfds.clear();
        let replacement = registry.snapshot(None)[0];
        assert_ne!(replacement.token, token);
        assert!(!replacement.original_root);
        assert!(registry.signal_token(&mut kernel, token, libc::SIGKILL).is_err());
        assert!(registry.signal_token(&mut kernel, 41, libc::SIGKILL).is_err());
        assert_eq!(kernel.signalled_pidfds(), vec![pidfd]);
        registry.signal_token(&mut kernel, replacement.token, libc::SIGCONT).unwrap();
        assert_eq!(kernel.signal_calls.len(), 2);
    }

    #[test]
    fn registry_reports_pidfd_open_failure() {
        let mut kernel = FakeKernel {
            pidfd_open_error: Some(libc::ESRCH),
            ..FakeKernel::default()
        };
        let mut registry = ProcessRegistry::new();

        let error = registry.track(&mut kernel, 41).unwrap_err();

        assert_eq!(error.raw_os_error(), Some(libc::ESRCH));
    }

    #[test]
    fn registry_signals_retained_pidfd_after_numeric_pid_reuse() {
        let mut kernel = FakeKernel::default();
        let mut registry = ProcessRegistry::new();
        registry.track(&mut kernel, 41).unwrap();
        let retained_fd = registry.process(41).unwrap().as_raw_fd();
        kernel.reuse_pid(41);

        assert_eq!(
            registry
                .send_signal(&mut kernel, 41, libc::SIGTERM)
                .unwrap(),
            SignalResult::Delivered
        );

        assert_eq!(kernel.signalled_pidfds(), vec![retained_fd]);
    }

    #[test]
    fn registry_removes_process_after_exited_signal_result() {
        let mut kernel = FakeKernel::default();
        let mut registry = ProcessRegistry::new();
        registry.track(&mut kernel, 41).unwrap();
        let pidfd = registry.process(41).unwrap().as_raw_fd();
        kernel.mark_exited(pidfd);

        let result = registry
            .send_signal(&mut kernel, 41, libc::SIGTERM)
            .unwrap();

        assert_eq!(result, SignalResult::Exited);
        assert!(registry.process(41).is_none());
    }

    #[test]
    fn registry_removes_process_when_pidfd_becomes_ready() {
        let mut kernel = FakeKernel::default();
        let mut registry = ProcessRegistry::new();
        registry.track(&mut kernel, 41).unwrap();
        let pidfd = registry.process(41).unwrap().as_raw_fd();
        kernel.mark_exited(pidfd);

        registry.remove_exited(&mut kernel).unwrap();

        assert!(registry.process(41).is_none());
    }

    #[test]
    fn discovers_current_cgroup_beneath_non_root_mount() {
        let location = CgroupLocation::discover(
            "0::/tenant/agent\n",
            "36 25 0:32 /tenant /sys/fs/cgroup rw - cgroup2 cgroup rw\n",
        )
        .unwrap();

        assert_eq!(location.current(), Path::new("/sys/fs/cgroup/agent"));
    }

    #[test]
    fn prefers_a_writable_cgroup2_mount_at_equal_root_depth() {
        let mountinfo = concat!(
            "36 25 0:32 /tenant /sys/fs/cgroup-ro ro,nosuid - cgroup2 cgroup rw\n",
            "37 25 0:33 /tenant /sys/fs/cgroup-rw rw,nosuid - cgroup2 cgroup rw\n",
        );

        let location = CgroupLocation::discover("0::/tenant/agent\n", mountinfo).unwrap();

        assert_eq!(location.current(), Path::new("/sys/fs/cgroup-rw/agent"));
    }

    #[test]
    fn ignores_v1_memberships_when_discovering_unified_cgroup() {
        let location = CgroupLocation::discover(
            "7:cpu,cpuacct:/tenant/wrong\n5:memory:/tenant/wrong\n0::/tenant/right\n",
            "36 25 0:32 /tenant /sys/fs/cgroup rw - cgroup2 cgroup rw\n",
        )
        .unwrap();

        assert_eq!(location.current(), Path::new("/sys/fs/cgroup/right"));
    }

    #[test]
    fn reports_missing_unified_membership() {
        let error = CgroupLocation::discover(
            "7:cpu,cpuacct:/tenant/agent\n",
            "36 25 0:32 /tenant /sys/fs/cgroup rw - cgroup2 cgroup rw\n",
        )
        .unwrap_err();

        assert!(error.to_string().contains("unified"));
    }

    #[test]
    fn reports_missing_cgroup2_mount() {
        let error = CgroupLocation::discover(
            "0::/tenant/agent\n",
            "36 25 0:32 /tenant /sys/fs/cgroup rw - cgroup cgroup rw\n",
        )
        .unwrap_err();

        assert!(error.to_string().contains("cgroup2"));
    }

    #[test]
    fn decodes_kernel_mountinfo_escapes() {
        let location = CgroupLocation::discover(
            "0::/tenant space/agent\n",
            "36 25 0:32 /tenant\\040space /sys/fs/cgroup\\040v2 rw - cgroup2 cgroup rw\n",
        )
        .unwrap();

        assert_eq!(location.current(), Path::new("/sys/fs/cgroup v2/agent"));
        assert_eq!(
            super::decode_mountinfo_path("/space\\040tab\\011line\\012slash\\134end").unwrap(),
            Path::new("/space tab\tline\nslash\\end")
        );
    }

    #[test]
    fn rejects_malformed_or_non_kernel_mountinfo_escapes() {
        for mountinfo in [
            "36 25 0:32 /tenant\\04 /sys/fs/cgroup rw - cgroup2 cgroup rw\n",
            "36 25 0:32 /tenant\\041 /sys/fs/cgroup rw - cgroup2 cgroup rw\n",
            "36 25 0:32 /tenant /sys/fs/cgroup\\x20 rw - cgroup2 cgroup rw\n",
        ] {
            let error = CgroupLocation::discover("0::/tenant/agent\n", mountinfo).unwrap_err();
            assert!(error.to_string().contains("escape"), "{error}");
        }
    }

    #[test]
    fn rejects_membership_outside_mount_root() {
        let error = CgroupLocation::discover(
            "0::/other/agent\n",
            "36 25 0:32 /tenant /sys/fs/cgroup rw - cgroup2 cgroup rw\n",
        )
        .unwrap_err();

        assert!(error.to_string().contains("mount root"));
    }

    #[test]
    fn rejects_traversal_in_membership_and_mount_paths() {
        for (membership, mountinfo) in [
            (
                "0::/tenant/../other\n",
                "36 25 0:32 /tenant /sys/fs/cgroup rw - cgroup2 cgroup rw\n",
            ),
            (
                "0::/tenant/agent\n",
                "36 25 0:32 /tenant /sys/fs/../escape rw - cgroup2 cgroup rw\n",
            ),
        ] {
            let error = CgroupLocation::discover(membership, mountinfo).unwrap_err();
            assert!(error.to_string().contains("unsafe"), "{error}");
        }
    }

    #[test]
    fn reads_nested_session_processes_without_following_symlinks() {
        let directory = DirectoryGuard::new("processes");
        fs::write(directory.path().join("cgroup.procs"), "41\n").unwrap();
        fs::write(directory.path().join("cgroup.events"), "populated 1\n").unwrap();
        fs::write(directory.path().join("cgroup.kill"), "").unwrap();
        let nested = directory.path().join("nested");
        fs::create_dir(&nested).unwrap();
        fs::write(nested.join("cgroup.procs"), "42\n43\n").unwrap();
        let mut cgroup = SessionCgroup::open_existing(directory.path().to_path_buf()).unwrap();

        assert_eq!(cgroup.process_ids().unwrap(), vec![41, 42, 43]);
        assert!(cgroup.populated().unwrap());
        cgroup.move_to_session(44).unwrap();
        assert!(cgroup.session_contains(44).unwrap());
        assert_eq!(fs::read_to_string(directory.path().join("cgroup.procs")).unwrap(), "44\n");
        cgroup.kill().unwrap();
        assert_eq!(fs::read_to_string(directory.path().join("cgroup.kill")).unwrap(), "1\n");

        let outside = DirectoryGuard::new("outside");
        fs::write(outside.path().join("cgroup.procs"), "99\n").unwrap();
        std::os::unix::fs::symlink(outside.path(), directory.path().join("escape")).unwrap();
        let error = cgroup.process_ids().unwrap_err();
        assert!(error.to_string().contains("symlink"));
    }

    #[test]
    fn rejects_invalid_pids_in_session_cgroup() {
        let directory = DirectoryGuard::new("invalid-pids");
        fs::write(directory.path().join("cgroup.procs"), "0\n").unwrap();
        fs::write(directory.path().join("cgroup.events"), "populated 1\n").unwrap();
        fs::write(directory.path().join("cgroup.kill"), "").unwrap();
        let cgroup = SessionCgroup::open_existing(directory.path().to_path_buf()).unwrap();

        let error = cgroup.process_ids().unwrap_err();

        assert!(error.to_string().contains("positive PID"));
    }

    #[test]
    fn cleans_partial_session_cgroup_when_required_files_are_missing() {
        let directory = DirectoryGuard::new("partial");
        let location = CgroupLocation::for_test(directory.path().to_path_buf());
        let candidate = directory.path().join("orion-session-test");

        let error = match SessionCgroup::create_named(&location, "orion-session-test") {
            Err(CgroupCreationError::Clean(error)) => error,
            Err(CgroupCreationError::Dirty { .. }) => panic!("partial cgroup should be clean"),
            Ok(_) => panic!("missing capabilities should reject the cgroup"),
        };

        assert!(error.to_string().contains("cgroup.procs"));
        assert!(!candidate.exists());
    }

    #[test]
    fn requires_each_session_cgroup_capability_file() {
        for missing in ["cgroup.procs", "cgroup.events", "cgroup.kill"] {
            let directory = DirectoryGuard::new(missing);
            for required in ["cgroup.procs", "cgroup.events", "cgroup.kill"] {
                if required != missing {
                    fs::write(directory.path().join(required), "").unwrap();
                }
            }

            let error = SessionCgroup::open_existing(directory.path().to_path_buf()).unwrap_err();

            assert!(error.to_string().contains(missing), "{error}");
        }
    }

    #[test]
    fn rejects_a_symlinked_current_cgroup_before_creation() {
        let mount = DirectoryGuard::new("mount");
        let outside = DirectoryGuard::new("symlink-current");
        let current = mount.path().join("current");
        std::os::unix::fs::symlink(outside.path(), &current).unwrap();
        let location = CgroupLocation {
            mount_point: mount.path().to_path_buf(),
            current,
        };

        let error = match SessionCgroup::create(&location) {
            Err(CgroupCreationError::Clean(error)) => error,
            Err(CgroupCreationError::Dirty { .. }) => panic!("no cgroup should be created"),
            Ok(_) => panic!("symlinked current cgroup should be rejected"),
        };

        assert!(error.to_string().contains("symlink"));
        assert!(fs::read_dir(outside.path()).unwrap().next().is_none());
    }

    #[test]
    fn refuses_to_remove_a_populated_session_cgroup() {
        let directory = DirectoryGuard::new("populated");
        fs::write(directory.path().join("cgroup.procs"), "41\n").unwrap();
        fs::write(directory.path().join("cgroup.events"), "populated 1\n").unwrap();
        fs::write(directory.path().join("cgroup.kill"), "").unwrap();
        let mut cgroup = SessionCgroup::open_existing(directory.path().to_path_buf()).unwrap();

        let error = cgroup.remove_if_empty().unwrap_err();

        assert_eq!(error.kind(), io::ErrorKind::WouldBlock);
        assert!(directory.path().exists());
    }

    #[test]
    fn attaches_held_child_before_releasing_it() {
        let calls = Rc::new(RefCell::new(Vec::new()));
        let held = FakeHeldChild::new(Rc::clone(&calls));
        let mut kernel = OrderingKernel {
            calls: Rc::clone(&calls),
            open_error: None,
        };

        let (held, backend) = configure_held_child(
            held,
            &mut kernel,
            |_| {
                calls.borrow_mut().push(StartupCall::AttachToCgroup);
                CgroupSetup::Attached(())
            },
            |_| panic!("successful cgroup setup must not warn"),
            |_, _| Ok::<_, io::Error>(()),
            |(), backend| backend,
        )
        .unwrap();

        assert!(matches!(backend, SelectedBackend::Cgroup { .. }));
        assert_eq!(
            *calls.borrow(),
            [
                StartupCall::ForkChildHeld,
                StartupCall::OpenRootPidfd,
                StartupCall::AttachToCgroup,
                StartupCall::ReleaseChild,
            ]
        );
        drop(held);
    }

    #[test]
    fn persists_one_fallback_warning_before_release_for_every_cgroup_failure_family() {
        for stage in ["discovery", "create", "open", "attach"] {
            let calls = Rc::new(RefCell::new(Vec::new()));
            let held = FakeHeldChild::new(Rc::clone(&calls));
            let mut kernel = OrderingKernel {
                calls: Rc::clone(&calls),
                open_error: None,
            };

            let (held, backend) = configure_held_child(
                held,
                &mut kernel,
                |_| {
                    calls.borrow_mut().push(StartupCall::CgroupFailed(stage));
                    CgroupSetup::CleanFailure(io::Error::new(
                        io::ErrorKind::PermissionDenied,
                        stage,
                    ))
                },
                |reason| {
                    assert!(!reason.is_empty());
                    assert!(reason.len() <= 4096);
                    calls
                        .borrow_mut()
                        .push(StartupCall::AppendDurableHostWarning);
                    Ok::<_, io::Error>(())
                },
                |_, _: &SelectedBackend<()>| Ok::<_, io::Error>(()),
                |(), backend| backend,
            )
            .unwrap();

            assert!(matches!(backend, SelectedBackend::Fallback { .. }));
            assert_eq!(
                *calls.borrow(),
                [
                    StartupCall::ForkChildHeld,
                    StartupCall::OpenRootPidfd,
                    StartupCall::CgroupFailed(stage),
                    StartupCall::AppendDurableHostWarning,
                    StartupCall::ReleaseChild,
                ]
            );
            drop(held);
        }
    }

    #[test]
    fn warning_persistence_failure_kills_and_reaps_held_child_without_release() {
        let calls = Rc::new(RefCell::new(Vec::new()));
        let held = FakeHeldChild::new(Rc::clone(&calls));
        let mut kernel = OrderingKernel {
            calls: Rc::clone(&calls),
            open_error: None,
        };

        let result = configure_held_child(
            held,
            &mut kernel,
            |_| {
                calls
                    .borrow_mut()
                    .push(StartupCall::CgroupFailed("attach"));
                CgroupSetup::CleanFailure(io::Error::new(
                    io::ErrorKind::PermissionDenied,
                    "attach",
                ))
            },
            |_| {
                calls
                    .borrow_mut()
                    .push(StartupCall::AppendDurableHostWarning);
                Err::<(), _>(io::Error::other("journal sync failed"))
            },
            |_, _: &SelectedBackend<()>| Ok::<_, io::Error>(()),
            |(), backend| backend,
        );

        assert!(result.is_err());
        assert_eq!(
            *calls.borrow(),
            [
                StartupCall::ForkChildHeld,
                StartupCall::OpenRootPidfd,
                StartupCall::CgroupFailed("attach"),
                StartupCall::AppendDurableHostWarning,
                StartupCall::KillHeldChild,
                StartupCall::ReapHeldChild,
            ]
        );
    }

    #[test]
    fn root_pidfd_failure_is_fatal_and_kills_and_reaps_held_child() {
        let calls = Rc::new(RefCell::new(Vec::new()));
        let held = FakeHeldChild::new(Rc::clone(&calls));
        let mut kernel = OrderingKernel {
            calls: Rc::clone(&calls),
            open_error: Some(libc::EMFILE),
        };

        let result = configure_held_child(
            held,
            &mut kernel,
            |_| -> CgroupSetup<()> { panic!("cgroup setup must not run without a root pidfd") },
            |_| panic!("root pidfd failure is not fallback"),
            |_, _: &SelectedBackend<()>| Ok::<_, io::Error>(()),
            |(), backend| backend,
        );

        assert!(result.is_err());
        assert_eq!(
            *calls.borrow(),
            [
                StartupCall::ForkChildHeld,
                StartupCall::OpenRootPidfd,
                StartupCall::KillHeldChild,
                StartupCall::ReapHeldChild,
            ]
        );
    }

    #[test]
    fn prepares_tracker_after_warning_and_before_releasing_fallback_child() {
        let calls = Rc::new(RefCell::new(Vec::new()));
        let held = FakeHeldChild::new(Rc::clone(&calls));
        let mut kernel = OrderingKernel {
            calls: Rc::clone(&calls),
            open_error: None,
        };

        let (held, ()) = configure_held_child(
            held,
            &mut kernel,
            |_| CgroupSetup::CleanFailure(io::Error::other("cgroup unavailable")),
            |_| {
                calls
                    .borrow_mut()
                    .push(StartupCall::AppendDurableHostWarning);
                Ok::<_, io::Error>(())
            },
            |_, _: &SelectedBackend<()>| {
                calls.borrow_mut().push(StartupCall::PrepareTracker);
                Ok::<_, io::Error>(())
            },
            |(), _| (),
        )
        .unwrap();

        assert_eq!(
            *calls.borrow(),
            [
                StartupCall::ForkChildHeld,
                StartupCall::OpenRootPidfd,
                StartupCall::AppendDurableHostWarning,
                StartupCall::PrepareTracker,
                StartupCall::ReleaseChild,
            ]
        );
        drop(held);
    }

    #[test]
    fn bounds_fallback_reason_at_a_utf8_boundary() {
        let detail = "é".repeat(4096);
        let error = io::Error::other(detail);

        let reason = super::bounded_fallback_reason(&error);

        assert!(!reason.is_empty());
        assert!(reason.len() <= 4096);
        assert!(reason.is_char_boundary(reason.len()));
    }

    #[test]
    fn attached_tracker_preparation_failure_aborts_before_cgroup_cleanup() {
        let calls = Rc::new(RefCell::new(Vec::new()));
        let state = Rc::new(RefCell::new(OwnershipState {
            process: ProcessPlace::Session,
            session_exists: true,
            fail_session_confirmation: false,
            fail_rollback: false,
            fail_removal: false,
            fail_cleanup_after_exit: false,
        }));
        let held = FakeHeldChild::with_ownership(Rc::clone(&calls), Rc::clone(&state))
            .failing_abort();
        let mut kernel = OrderingKernel {
            calls: Rc::clone(&calls),
            open_error: None,
        };

        let result = configure_held_child(
            held,
            &mut kernel,
            |_| CgroupSetup::Attached(StatefulCgroup {
                calls: Rc::clone(&calls),
                state: Rc::clone(&state),
            }),
            |_| panic!("attached cgroup must not warn"),
            |_, _| {
                calls.borrow_mut().push(StartupCall::PrepareTracker);
                Err::<(), _>(io::Error::other("tracker preparation failed"))
            },
            |(), _| (),
        );

        let error = match result {
            Err(error) => error,
            Ok(_) => panic!("tracker preparation failure must be fatal"),
        };
        assert!(error.to_string().contains("tracker preparation failed"));
        assert!(error.to_string().contains("held child abort failed"));
        assert_eq!(state.borrow().process, ProcessPlace::Dead);
        assert!(!state.borrow().session_exists);
        assert_eq!(
            *calls.borrow(),
            [
                StartupCall::ForkChildHeld,
                StartupCall::OpenRootPidfd,
                StartupCall::PrepareTracker,
                StartupCall::KillHeldChild,
                StartupCall::ReapHeldChild,
                StartupCall::CleanupAfterChildDeath,
            ]
        );
    }

    #[test]
    fn attached_release_failure_composes_cleanup_error_after_abort() {
        let calls = Rc::new(RefCell::new(Vec::new()));
        let state = Rc::new(RefCell::new(OwnershipState {
            process: ProcessPlace::Session,
            session_exists: true,
            fail_session_confirmation: false,
            fail_rollback: false,
            fail_removal: false,
            fail_cleanup_after_exit: true,
        }));
        let held = FakeHeldChild::with_ownership(Rc::clone(&calls), Rc::clone(&state))
            .failing_release();
        let mut kernel = OrderingKernel {
            calls: Rc::clone(&calls),
            open_error: None,
        };

        let result = configure_held_child(
            held,
            &mut kernel,
            |_| CgroupSetup::Attached(StatefulCgroup {
                calls: Rc::clone(&calls),
                state: Rc::clone(&state),
            }),
            |_| panic!("attached cgroup must not warn"),
            |_, _| {
                calls.borrow_mut().push(StartupCall::PrepareTracker);
                Ok::<_, io::Error>(())
            },
            |(), _| (),
        );

        let error = match result {
            Err(error) => error,
            Ok(_) => panic!("release failure must be fatal"),
        };
        assert!(error.to_string().contains("held child release failed"));
        assert!(error.to_string().contains("post-reap cleanup failed"));
        assert_eq!(state.borrow().process, ProcessPlace::Dead);
        assert!(state.borrow().session_exists);
        assert_eq!(
            *calls.borrow(),
            [
                StartupCall::ForkChildHeld,
                StartupCall::OpenRootPidfd,
                StartupCall::PrepareTracker,
                StartupCall::ReleaseChildFailed,
                StartupCall::KillHeldChild,
                StartupCall::ReapHeldChild,
                StartupCall::CleanupAfterChildDeath,
            ]
        );
    }

    #[test]
    fn dirty_setup_composes_explicit_post_reap_cleanup_error() {
        let calls = Rc::new(RefCell::new(Vec::new()));
        let state = Rc::new(RefCell::new(OwnershipState {
            process: ProcessPlace::Parent,
            session_exists: true,
            fail_session_confirmation: false,
            fail_rollback: false,
            fail_removal: false,
            fail_cleanup_after_exit: true,
        }));
        let held = FakeHeldChild::with_ownership(Rc::clone(&calls), Rc::clone(&state));
        let mut kernel = OrderingKernel {
            calls: Rc::clone(&calls),
            open_error: None,
        };

        let result = configure_held_child(
            held,
            &mut kernel,
            |_| CgroupSetup::DirtyFailure {
                error: io::Error::other("partial cgroup cleanup failed"),
                cgroup: StatefulCgroup {
                    calls: Rc::clone(&calls),
                    state: Rc::clone(&state),
                },
            },
            |_| panic!("dirty cgroup setup must not warn"),
            |_, _| Ok::<_, io::Error>(()),
            |(), _| (),
        );

        let error = match result {
            Err(error) => error,
            Ok(_) => panic!("dirty cgroup setup must be fatal"),
        };
        assert!(error.to_string().contains("partial cgroup cleanup failed"));
        assert!(error.to_string().contains("post-reap cleanup failed"));
        assert_eq!(
            *calls.borrow(),
            [
                StartupCall::ForkChildHeld,
                StartupCall::OpenRootPidfd,
                StartupCall::KillHeldChild,
                StartupCall::ReapHeldChild,
                StartupCall::CleanupAfterChildDeath,
            ]
        );
    }

    #[test]
    fn partial_creation_cleanup_failure_is_fatal_before_release() {
        let calls = Rc::new(RefCell::new(Vec::new()));
        let state = Rc::new(RefCell::new(OwnershipState {
            process: ProcessPlace::Parent,
            session_exists: true,
            fail_session_confirmation: false,
            fail_rollback: false,
            fail_removal: false,
            fail_cleanup_after_exit: false,
        }));
        let held = FakeHeldChild::with_ownership(Rc::clone(&calls), Rc::clone(&state));
        let mut kernel = OrderingKernel {
            calls: Rc::clone(&calls),
            open_error: None,
        };

        let result = configure_held_child(
            held,
            &mut kernel,
            |_| CgroupSetup::DirtyFailure {
                error: io::Error::other("partial cgroup cleanup failed"),
                cgroup: StatefulCgroup {
                    calls: Rc::clone(&calls),
                    state: Rc::clone(&state),
                },
            },
            |_| {
                calls
                    .borrow_mut()
                    .push(StartupCall::AppendDurableHostWarning);
                Ok::<_, io::Error>(())
            },
            |_, _| Ok::<_, io::Error>(()),
            |(), backend| backend,
        );

        let error = match result {
            Err(error) => error,
            Ok(_) => panic!("unclean partial creation must be fatal"),
        };
        assert!(error.to_string().contains("partial cgroup cleanup failed"));
        assert_eq!(state.borrow().process, ProcessPlace::Dead);
        assert!(!state.borrow().session_exists);
        assert_eq!(
            *calls.borrow(),
            [
                StartupCall::ForkChildHeld,
                StartupCall::OpenRootPidfd,
                StartupCall::KillHeldChild,
                StartupCall::ReapHeldChild,
                StartupCall::CleanupAfterChildDeath,
            ]
        );
    }

    #[test]
    fn rolls_back_post_write_attach_failure_before_warning_and_release() {
        let calls = Rc::new(RefCell::new(Vec::new()));
        let state = Rc::new(RefCell::new(OwnershipState {
            process: ProcessPlace::Parent,
            session_exists: true,
            fail_session_confirmation: true,
            fail_rollback: false,
            fail_removal: false,
            fail_cleanup_after_exit: false,
        }));
        let held = FakeHeldChild::with_ownership(Rc::clone(&calls), Rc::clone(&state));
        let mut kernel = OrderingKernel {
            calls: Rc::clone(&calls),
            open_error: None,
        };

        let (held, backend) = configure_held_child(
            held,
            &mut kernel,
            |_| {
                attach_with_rollback(
                    StatefulCgroup {
                        calls: Rc::clone(&calls),
                        state: Rc::clone(&state),
                    },
                    41,
                )
            },
            |_| {
                calls
                    .borrow_mut()
                    .push(StartupCall::AppendDurableHostWarning);
                Ok::<_, io::Error>(())
            },
            |_, _| Ok::<_, io::Error>(()),
            |(), backend| backend,
        )
        .unwrap();

        assert!(matches!(backend, SelectedBackend::Fallback { .. }));
        assert_eq!(state.borrow().process, ProcessPlace::Parent);
        assert!(!state.borrow().session_exists);
        assert_eq!(
            *calls.borrow(),
            [
                StartupCall::ForkChildHeld,
                StartupCall::OpenRootPidfd,
                StartupCall::MoveToSession,
                StartupCall::ConfirmSession,
                StartupCall::MoveBackToParent,
                StartupCall::ConfirmParent,
                StartupCall::ConfirmSessionEmpty,
                StartupCall::RemoveSessionCgroup,
                StartupCall::AppendDurableHostWarning,
                StartupCall::ReleaseChild,
            ]
        );
        drop(held);
    }

    #[test]
    fn fatal_attach_rollback_failure_kills_and_reaps_before_cleanup_without_warning() {
        let calls = Rc::new(RefCell::new(Vec::new()));
        let state = Rc::new(RefCell::new(OwnershipState {
            process: ProcessPlace::Parent,
            session_exists: true,
            fail_session_confirmation: true,
            fail_rollback: true,
            fail_removal: false,
            fail_cleanup_after_exit: false,
        }));
        let held = FakeHeldChild::with_ownership(Rc::clone(&calls), Rc::clone(&state));
        let mut kernel = OrderingKernel {
            calls: Rc::clone(&calls),
            open_error: None,
        };

        let result = configure_held_child(
            held,
            &mut kernel,
            |_| {
                attach_with_rollback(
                    StatefulCgroup {
                        calls: Rc::clone(&calls),
                        state: Rc::clone(&state),
                    },
                    41,
                )
            },
            |_| {
                calls
                    .borrow_mut()
                    .push(StartupCall::AppendDurableHostWarning);
                Ok::<_, io::Error>(())
            },
            |_, _| Ok::<_, io::Error>(()),
            |(), backend| backend,
        );

        let error = match result {
            Err(error) => error,
            Ok(_) => panic!("failed rollback must be fatal"),
        };
        assert!(error.to_string().contains("session confirmation failed"));
        assert_eq!(state.borrow().process, ProcessPlace::Dead);
        assert!(!state.borrow().session_exists);
        assert_eq!(
            *calls.borrow(),
            [
                StartupCall::ForkChildHeld,
                StartupCall::OpenRootPidfd,
                StartupCall::MoveToSession,
                StartupCall::ConfirmSession,
                StartupCall::MoveBackToParent,
                StartupCall::KillHeldChild,
                StartupCall::ReapHeldChild,
                StartupCall::CleanupAfterChildDeath,
            ]
        );
    }

    #[derive(Clone)]
    struct TerminationCgroup {
        processes: Rc<RefCell<Vec<libc::pid_t>>>,
        populated: Rc<RefCell<Vec<bool>>>,
        kill_count: Rc<RefCell<usize>>,
        removed: Rc<RefCell<bool>>,
    }

    impl CgroupOwnership for TerminationCgroup {
        fn move_to_session(&mut self, _pid: libc::pid_t) -> io::Result<()> {
            unreachable!()
        }

        fn session_contains(&self, pid: libc::pid_t) -> io::Result<bool> {
            Ok(self.processes.borrow().contains(&pid))
        }

        fn move_to_parent(&mut self, _pid: libc::pid_t) -> io::Result<()> {
            unreachable!()
        }

        fn parent_contains(&self, _pid: libc::pid_t) -> io::Result<bool> {
            unreachable!()
        }

        fn populated(&self) -> io::Result<bool> {
            let mut values = self.populated.borrow_mut();
            if values.len() > 1 {
                return Ok(values.remove(0));
            }
            Ok(values.first().copied().unwrap_or(false))
        }

        fn remove_session(&mut self) -> io::Result<()> {
            *self.removed.borrow_mut() = true;
            Ok(())
        }

        fn cleanup_after_child_exit(&mut self) -> io::Result<()> {
            *self.removed.borrow_mut() = true;
            Ok(())
        }

        fn process_ids(&self) -> io::Result<Vec<libc::pid_t>> {
            Ok(self.processes.borrow().clone())
        }

        fn kill(&self) -> io::Result<()> {
            *self.kill_count.borrow_mut() += 1;
            Ok(())
        }
    }

    #[derive(Default)]
    struct FakeProcessSource {
        snapshots: Vec<HashMap<libc::pid_t, LinuxProcess>>,
        validations: HashMap<libc::pid_t, Vec<Option<LinuxProcess>>>,
        snapshot_calls: usize,
    }

    impl ProcessSource for FakeProcessSource {
        fn snapshot(
            &mut self,
            _pty: super::super::PtySlaveIdentity,
        ) -> io::Result<HashMap<libc::pid_t, LinuxProcess>> {
            self.snapshot_calls += 1;
            if self.snapshots.len() > 1 {
                return Ok(self.snapshots.remove(0));
            }
            Ok(self.snapshots.first().cloned().unwrap_or_default())
        }

        fn process(
            &mut self,
            pid: libc::pid_t,
            _pty: super::super::PtySlaveIdentity,
        ) -> io::Result<Option<LinuxProcess>> {
            let Some(values) = self.validations.get_mut(&pid) else {
                return Ok(None);
            };
            if values.len() > 1 {
                return Ok(values.remove(0));
            }
            Ok(values.first().cloned().flatten())
        }
    }

    fn process(
        parent: libc::pid_t,
        session: libc::pid_t,
        process_group: libc::pid_t,
        start: u128,
    ) -> LinuxProcess {
        LinuxProcess {
            parent,
            session,
            process_group,
            start,
            holds_pty: false,
        }
    }

    fn test_pty() -> super::super::PtySlaveIdentity {
        super::super::PtySlaveIdentity {
            device: 1,
            inode: 2,
        }
    }

    fn fallback_tree(kernel: &mut FakeKernel) -> ActiveProcessTree<()> {
        let mut registry = ProcessRegistry::new();
        registry.track(kernel, 41).unwrap();
        ActiveProcessTree::activate(
            SelectedBackend::Fallback { registry },
            41,
            100,
            test_pty(),
        )
    }

    #[test]
    fn refresh_does_not_issue_tokens_for_unreaped_exited_processes() {
        let mut kernel = FakeKernel::default();
        let mut tree = fallback_tree(&mut kernel);
        let pidfd = match &tree.backend {
            SelectedBackend::Fallback { registry } => registry.process(41).unwrap().as_raw_fd(),
            _ => unreachable!(),
        };
        kernel.mark_exited(pidfd);
        kernel.exited_on_open.insert(41);
        let zombie = process(std::process::id() as libc::pid_t, 41, 41, 100);
        let mut source = FakeProcessSource {
            snapshots: vec![HashMap::from([(41, zombie.clone())])],
            validations: HashMap::from([(41, vec![Some(zombie)])]),
            ..FakeProcessSource::default()
        };
        tree.refresh_with(&mut kernel, &mut source).unwrap();
        match &tree.backend {
            SelectedBackend::Fallback { registry } => assert!(registry.snapshot(Some(41)).is_empty()),
            _ => unreachable!(),
        }
        let cgroup = TerminationCgroup {
            processes: Rc::new(RefCell::new(vec![41])),
            populated: Rc::new(RefCell::new(vec![true])),
            kill_count: Rc::new(RefCell::new(0)),
            removed: Rc::new(RefCell::new(false)),
        };
        let mut registry = ProcessRegistry::new();
        super::refresh_cgroup_registry(&mut registry, &cgroup, &mut kernel).unwrap();
        assert!(registry.snapshot(Some(41)).is_empty());
    }

    #[test]
    fn cgroup_refresh_retires_tokens_that_leave_the_owned_boundary() {
        let processes = Rc::new(RefCell::new(vec![41, 42]));
        let cgroup = TerminationCgroup {
            processes: Rc::clone(&processes),
            populated: Rc::new(RefCell::new(vec![true])),
            kill_count: Rc::new(RefCell::new(0)),
            removed: Rc::new(RefCell::new(false)),
        };
        let mut kernel = FakeKernel::default();
        let mut registry = ProcessRegistry::new();
        super::refresh_cgroup_registry(&mut registry, &cgroup, &mut kernel).unwrap();
        let before = registry.snapshot(Some(41));
        let token = before.iter().find(|entry| entry.pid == 42).unwrap().token;
        *processes.borrow_mut() = vec![41];
        super::refresh_cgroup_registry(&mut registry, &cgroup, &mut kernel).unwrap();
        assert_eq!(registry.snapshot(Some(41)).len(), 1);
        assert!(registry.signal_token(&mut kernel, token, libc::SIGKILL).is_err());
        assert!(kernel.signal_calls.is_empty());
        processes.borrow_mut().push(42);
        super::refresh_cgroup_registry(&mut registry, &cgroup, &mut kernel).unwrap();
        let after = registry.snapshot(Some(41));
        assert_ne!(after.iter().find(|entry| entry.pid == 42).unwrap().token, token);
    }

    #[test]
    fn cgroup_graceful_signal_uses_pidfds_and_force_uses_cgroup_kill_without_proc_scan() {
        let processes = Rc::new(RefCell::new(vec![41, 42]));
        let kill_count = Rc::new(RefCell::new(0));
        let mut kernel = FakeKernel::default();
        let mut registry = ProcessRegistry::new();
        registry.track(&mut kernel, 41).unwrap();
        let mut tree = ActiveProcessTree::activate(
            SelectedBackend::Cgroup {
                registry,
                cgroup: TerminationCgroup {
                    processes: Rc::clone(&processes),
                    populated: Rc::new(RefCell::new(vec![true])),
                    kill_count: Rc::clone(&kill_count),
                    removed: Rc::new(RefCell::new(false)),
                },
            },
            41,
            100,
            test_pty(),
        );
        let mut source = FakeProcessSource::default();

        tree.signal_with(&mut kernel, &mut source, libc::SIGTERM)
            .unwrap();
        tree.signal_with(&mut kernel, &mut source, libc::SIGKILL)
            .unwrap();

        assert_eq!(kernel.signal_calls.len(), 2);
        assert!(kernel.signal_calls.iter().all(|(_, signal)| *signal == libc::SIGTERM));
        assert_eq!(*kill_count.borrow(), 1);
        assert_eq!(source.snapshot_calls, 0);
    }

    #[test]
    fn cgroup_completion_waits_for_unpopulated_and_a_final_no_children_reap() {
        let removed = Rc::new(RefCell::new(false));
        let mut kernel = FakeKernel::default();
        kernel.reap_results = vec![
            ReapResult::Reaped(52),
            ReapResult::Running,
            ReapResult::Running,
            ReapResult::NoChildren,
        ];
        let mut registry = ProcessRegistry::new();
        registry.track(&mut kernel, 41).unwrap();
        let mut tree = ActiveProcessTree::activate(
            SelectedBackend::Cgroup {
                registry,
                cgroup: TerminationCgroup {
                    processes: Rc::new(RefCell::new(Vec::new())),
                    populated: Rc::new(RefCell::new(vec![true, false, false])),
                    kill_count: Rc::new(RefCell::new(0)),
                    removed: Rc::clone(&removed),
                },
            },
            41,
            100,
            test_pty(),
        );
        tree.mark_root_reaped();
        let mut source = FakeProcessSource::default();

        assert!(tree.is_live_with(&mut kernel, &mut source).unwrap());
        assert!(tree.is_live_with(&mut kernel, &mut source).unwrap());
        assert!(!tree.is_live_with(&mut kernel, &mut source).unwrap());

        assert_eq!(kernel.reaped_pids, vec![52]);
        assert!(*removed.borrow());
        assert_eq!(source.snapshot_calls, 0);
    }

    #[test]
    fn fallback_requires_a_new_explicit_signal_for_a_late_descendant() {
        let mut kernel = FakeKernel::default();
        let mut tree = fallback_tree(&mut kernel);
        let root = process(7, 41, 41, 100);
        let first_child = process(41, 41, 41, 200);
        let late_child = process(42, 43, 43, 300);
        let mut source = FakeProcessSource {
            snapshots: vec![
                HashMap::from([(41, root.clone()), (42, first_child.clone())]),
                HashMap::from([
                    (41, root.clone()),
                    (42, first_child.clone()),
                    (43, late_child.clone()),
                ]),
            ],
            validations: HashMap::from([
                (42, vec![Some(first_child)]),
                (43, vec![Some(late_child)]),
            ]),
            snapshot_calls: 0,
        };

        tree.signal_with(&mut kernel, &mut source, libc::SIGTERM)
            .unwrap();
        tree.refresh_with(&mut kernel, &mut source).unwrap();

        let late_pidfd = *kernel.opened_pidfds.get(&43).unwrap().last().unwrap();
        assert!(!kernel.signal_calls.contains(&(late_pidfd, libc::SIGTERM)));
        let delivered = kernel.signal_calls.clone();
        tree.signal_foreground_with(&mut kernel, &mut source, 999, libc::SIGINT).unwrap();
        tree.mark_root_reaped();
        assert!(tree.is_live_with(&mut kernel, &mut source).unwrap());
        assert_eq!(kernel.signal_calls, delivered);
        tree.signal_with(&mut kernel, &mut source, libc::SIGKILL).unwrap();
        assert!(kernel.signal_calls.contains(&(late_pidfd, libc::SIGKILL)));
    }

    #[test]
    fn reused_root_session_does_not_admit_unrelated_processes_after_reaping() {
        let mut kernel = FakeKernel::default();
        let mut tree = fallback_tree(&mut kernel);
        tree.mark_root_reaped();
        let unrelated = process(900, 41, 41, 999);
        let adopted = process(std::process::id() as libc::pid_t, 88, 88, 200);
        let mut source = FakeProcessSource {
            snapshots: vec![HashMap::from([(77, unrelated.clone()), (42, adopted.clone())])],
            validations: HashMap::from([(77, vec![Some(unrelated)]), (42, vec![Some(adopted)])]),
            snapshot_calls: 0,
        };
        tree.signal_with(&mut kernel, &mut source, libc::SIGTERM).unwrap();
        assert!(!kernel.opened_pidfds.contains_key(&77));
        let adopted_fd = kernel.opened_pidfds[&42][0];
        assert_eq!(kernel.signal_calls, vec![(adopted_fd, libc::SIGTERM)]);
    }

    #[test]
    fn failed_post_open_validation_does_not_register_a_candidate() {
        struct UnverifiableCandidate;

        impl ProcessSource for UnverifiableCandidate {
            fn snapshot(
                &mut self,
                _: super::super::PtySlaveIdentity,
            ) -> io::Result<HashMap<libc::pid_t, LinuxProcess>> {
                Ok(HashMap::from([
                    (41, process(7, 41, 41, 100)),
                    (42, process(41, 41, 41, 200)),
                ]))
            }

            fn process(
                &mut self,
                _: libc::pid_t,
                _: super::super::PtySlaveIdentity,
            ) -> io::Result<Option<LinuxProcess>> {
                Err(io::Error::from_raw_os_error(libc::EACCES))
            }
        }

        let mut kernel = FakeKernel::default();
        let mut tree = fallback_tree(&mut kernel);
        let error = tree
            .signal_with(&mut kernel, &mut UnverifiableCandidate, libc::SIGTERM)
            .unwrap_err();
        assert_eq!(error.raw_os_error(), Some(libc::EACCES));
        assert!(kernel.opened_pidfds.contains_key(&42));
        assert!(kernel.signal_calls.is_empty());
        let SelectedBackend::Fallback { registry } = &tree.backend else {
            unreachable!()
        };
        assert!(!registry.contains_pid(42));
        assert!(!tree.fallback_live.contains_key(&42));
    }

    #[test]
    fn inaccessible_owned_process_cannot_be_forgotten_or_signalled() {
        struct InaccessibleSource;
        impl ProcessSource for InaccessibleSource {
            fn snapshot(&mut self, _: super::super::PtySlaveIdentity)
                -> io::Result<HashMap<libc::pid_t, LinuxProcess>> {
                Ok(HashMap::new())
            }
            fn process(&mut self, _: libc::pid_t, _: super::super::PtySlaveIdentity)
                -> io::Result<Option<LinuxProcess>> {
                Err(io::Error::from_raw_os_error(libc::EACCES))
            }
        }
        let mut kernel = FakeKernel::default();
        let mut tree = fallback_tree(&mut kernel);
        let error = tree.signal_with(&mut kernel, &mut InaccessibleSource, libc::SIGTERM).unwrap_err();
        assert_eq!(error.raw_os_error(), Some(libc::EACCES));
        assert!(kernel.signal_calls.is_empty());
        assert_eq!(tree.fallback_live.get(&41), Some(&100));
        let SelectedBackend::Fallback { registry } = &tree.backend else { unreachable!() };
        assert!(registry.contains_pid(41));
    }

    #[test]
    fn fallback_discards_a_reused_pid_when_post_pidfd_validation_is_unrelated() {
        let mut kernel = FakeKernel::default();
        let mut tree = fallback_tree(&mut kernel);
        let root = process(7, 41, 41, 100);
        let candidate = process(41, 41, 41, 200);
        let unrelated = process(900, 900, 900, 201);
        let mut source = FakeProcessSource {
            snapshots: vec![HashMap::from([(41, root), (77, candidate)])],
            validations: HashMap::from([(77, vec![Some(unrelated)])]),
            snapshot_calls: 0,
        };

        tree.signal_with(&mut kernel, &mut source, libc::SIGTERM)
            .unwrap();

        let reused_pidfd = *kernel.opened_pidfds.get(&77).unwrap().last().unwrap();
        assert!(!kernel.signal_calls.iter().any(|(fd, _)| *fd == reused_pidfd));
    }

    #[test]
    fn foreground_signal_filters_the_process_group_to_owned_pidfds() {
        let mut kernel = FakeKernel::default();
        let mut tree = fallback_tree(&mut kernel);
        let root = process(7, 41, 41, 100);
        let owned = process(41, 41, 88, 200);
        let unrelated = process(900, 900, 88, 300);
        let mut source = FakeProcessSource {
            snapshots: vec![HashMap::from([
                (41, root.clone()),
                (42, owned.clone()),
                (77, unrelated.clone()),
            ])],
            validations: HashMap::from([
                (41, vec![Some(root)]),
                (42, vec![Some(owned)]),
                (77, vec![Some(unrelated)]),
            ]),
            snapshot_calls: 0,
        };

        tree.signal_foreground_with(&mut kernel, &mut source, 88, libc::SIGINT)
            .unwrap();

        let owned_pidfd = *kernel.opened_pidfds.get(&42).unwrap().last().unwrap();
        assert_eq!(kernel.signal_calls, vec![(owned_pidfd, libc::SIGINT)]);
        assert!(!kernel.opened_pidfds.contains_key(&77));
    }

    #[test]
    fn fallback_requires_stable_empty_and_no_remaining_adopted_child() {
        let mut kernel = FakeKernel::default();
        let mut tree = fallback_tree(&mut kernel);
        tree.mark_root_reaped();
        let mut source = FakeProcessSource {
            snapshots: vec![HashMap::new()],
            validations: HashMap::new(),
            snapshot_calls: 0,
        };
        kernel.reap_results = vec![
            ReapResult::Running,
            ReapResult::NoChildren,
            ReapResult::NoChildren,
        ];

        assert!(tree.is_live_with(&mut kernel, &mut source).unwrap());
        assert!(tree.is_live_with(&mut kernel, &mut source).unwrap());
        assert!(!tree.is_live_with(&mut kernel, &mut source).unwrap());
    }
}
