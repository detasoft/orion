#![cfg(unix)]

use std::fs;
use std::io;
#[cfg(target_os = "linux")]
use std::os::unix::ffi::OsStrExt;
use std::os::unix::net::UnixStream;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, ExitStatus, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use orion_session_host::host::{self, OwnedControlFrame};
use orion_session_host::journal::{self, Metadata};
use orion_session_host::protocol::{
    self, MAX_PAYLOAD_LENGTH, control_message, event_type,
};

const TIMEOUT: Duration = Duration::from_secs(10);
static NEXT_DIRECTORY: AtomicU64 = AtomicU64::new(0);

#[test]
fn failed_exec_never_crosses_the_successful_launch_boundary() {
    let directory = DirectoryGuard::new(temporary_directory("failed-exec"));
    let output = Command::new(env!("CARGO_BIN_EXE_session-host"))
        .args(base_arguments(
            directory.path(),
            "failed-exec",
            "xterm-256color",
            80,
            24,
        ))
        .args(["--", "/definitely/missing/orion-command"])
        .output()
        .unwrap();

    assert_eq!(output.status.code(), Some(70));
    let metadata = journal::read_metadata(directory.path()).unwrap();
    assert_eq!(metadata.child_pid, None);
    let events = journal::read(directory.path(), 0).unwrap().events;
    assert!(events.iter().all(|event| event.event_type != event_type::PROCESS_STARTED));
}

#[cfg(target_os = "linux")]
#[test]
fn landlock_restricts_child_and_grandchild_without_restricting_host() {
    let directory = temporary_directory("landlock-boundary");
    let workspace = directory.join("workspace");
    let credentials = directory.join("credentials");
    let denied_temporary = directory.join("denied-temporary");
    fs::create_dir_all(&workspace).unwrap();
    fs::create_dir_all(&credentials).unwrap();
    fs::create_dir_all(&denied_temporary).unwrap();
    fs::write(workspace.join("allowed"), b"allowed\n").unwrap();
    fs::create_dir(workspace.join("listable")).unwrap();
    fs::write(workspace.join("listable/entry"), b"hidden contents\n").unwrap();
    fs::write(workspace.join("writable"), b"before\n").unwrap();
    fs::create_dir(workspace.join("mutable")).unwrap();
    fs::write(credentials.join("secret"), b"secret\n").unwrap();

    let mut rules = ["/bin", "/usr", "/lib", "/lib64", "/etc"]
        .into_iter()
        .filter_map(|path| fs::canonicalize(path).ok().map(|path| (path, 13_u64)))
        .collect::<Vec<_>>();
    if let Ok(device_directory) = fs::canonicalize("/dev") {
        rules.push((device_directory, 32_782));
    }
    rules.extend([
        (fs::canonicalize(workspace.join("allowed")).unwrap(), 4),
        (fs::canonicalize(workspace.join("listable")).unwrap(), 8),
        (fs::canonicalize(workspace.join("writable")).unwrap(), 16_390),
        (fs::canonicalize(workspace.join("mutable")).unwrap(), 298),
    ]);
    rules.sort_by(|left, right| {
        left.0
            .as_os_str()
            .as_bytes()
            .cmp(right.0.as_os_str().as_bytes())
    });
    rules.dedup_by(|left, right| left.0 == right.0);
    let policy = directory.join("policy.cbor");
    fs::write(&policy, encode_policy(&rules)).unwrap();

    if current_landlock_abi() < 9 {
        assert_unsupported_landlock_modes(&directory, &policy);
        return;
    }

    let script = concat!(
        "IFS= read -r direct < \"$1/allowed\" && test \"$direct\" = allowed || exit 91; ",
        "for listed in \"$1/listable\"/*; do test \"$listed\" = \"$1/listable/entry\" || exit 92; done; ",
        "printf updated > \"$1/writable\" || exit 93; ",
        "printf created > \"$1/mutable/new\" || exit 94; ",
        "rm \"$1/mutable/new\" || exit 95; ",
        "if IFS= read -r denied < \"$2/secret\"; then exit 96; fi; ",
        "if printf denied > \"$3/new\"; then exit 97; fi; ",
        "/bin/sh -c '",
        "IFS= read -r nested < \"$1/allowed\" && test \"$nested\" = allowed || exit 98; ",
        "if IFS= read -r denied < \"$2/secret\"; then exit 99; fi; ",
        "if printf denied > \"$3/new\"; then exit 100; fi",
        "' nested \"$1\" \"$2\" \"$3\" || exit $?; printf READY; sleep 30",
    );
    let mut host = HostGuard::spawn_with_policy(
        directory,
        &policy,
        &[
            "/bin/sh",
            "-c",
            script,
            "boundary",
            workspace.to_str().unwrap(),
            credentials.to_str().unwrap(),
            denied_temporary.to_str().unwrap(),
        ],
    );
    wait_for_output(host.directory(), b"READY");
    assert_eq!(fs::read(workspace.join("writable")).unwrap(), b"updated");
    assert!(!workspace.join("mutable/new").exists());
    assert!(!denied_temporary.join("new").exists());

    let metadata = journal::read_metadata(host.directory()).unwrap();
    assert_eq!(
        metadata.sandbox.enforcement,
        journal::SandboxEnforcement::Landlock
    );
    let mut stream = connect(host.directory());
    let status = request(&mut stream, control_message::STATUS, 1, &[]);
    assert_eq!(status.message_type, control_message::STATUS_RESPONSE);
    let terminate = [1_u8, 0, 0, 0, 0, 0, 0, 0];
    let terminated = request(&mut stream, control_message::TERMINATE, 2, &terminate);
    assert_eq!(terminated.message_type, control_message::ACCEPTED);
    drop(stream);
    assert!(host.wait().success());
}

#[test]
fn invalid_grants_are_fatal_even_with_unsandboxed_fallback() {
    let directory = DirectoryGuard::new(temporary_directory("landlock-invalid-grant"));
    fs::create_dir_all(directory.path()).unwrap();
    let target = directory.path().join("target");
    let link = directory.path().join("grant-link");
    fs::write(&target, b"target").unwrap();
    std::os::unix::fs::symlink(&target, &link).unwrap();
    let policy = directory.path().join("policy.cbor");
    let cases = [
        (directory.path().join("missing"), 12),
        (link, 12),
        (target, 8),
    ];
    for (grant, rights) in cases {
        fs::write(&policy, encode_policy(&[(grant, rights)])).unwrap();
        let output = Command::new(env!("CARGO_BIN_EXE_session-host"))
            .args(base_arguments(
                directory.path(),
                "landlock-invalid-grant",
                "xterm-256color",
                80,
                24,
            ))
            .args([
                "--sandbox-policy",
                policy.to_str().unwrap(),
                "--sandbox-unavailable",
                "run-unsandboxed",
                "--",
                "/bin/sh",
                "-c",
                "exit 0",
            ])
            .output()
            .unwrap();
        assert_eq!(output.status.code(), Some(70));
    }
}

#[cfg(target_os = "linux")]
fn current_landlock_abi() -> i64 {
    unsafe {
        libc::syscall(
            libc::SYS_landlock_create_ruleset,
            std::ptr::null::<libc::c_void>(),
            0,
            1,
        )
    }
}

#[cfg(target_os = "linux")]
fn assert_unsupported_landlock_modes(directory: &Path, policy: &Path) {
    let base = base_arguments(directory, "landlock-unsupported", "xterm-256color", 80, 24);
    let failed = Command::new(env!("CARGO_BIN_EXE_session-host"))
        .args(&base)
        .args(["--sandbox-policy", policy.to_str().unwrap(), "--", "/bin/sh", "-c", "exit 0"])
        .output()
        .unwrap();
    assert_eq!(failed.status.code(), Some(70));
    let fallback = Command::new(env!("CARGO_BIN_EXE_session-host"))
        .args(&base)
        .args([
            "--sandbox-policy",
            policy.to_str().unwrap(),
            "--sandbox-unavailable",
            "run-unsandboxed",
            "--",
            "/bin/sh",
            "-c",
            "exit 0",
        ])
        .output()
        .unwrap();
    assert!(fallback.status.success(), "{}", String::from_utf8_lossy(&fallback.stderr));
}

#[cfg(not(target_os = "linux"))]
#[test]
fn requested_landlock_policy_fails_closed_or_uses_explicit_fallback() {
    let directory = DirectoryGuard::new(temporary_directory("sandbox-unavailable"));
    fs::create_dir_all(directory.path()).unwrap();
    let policy = directory.path().join("policy.cbor");
    fs::write(&policy, encode_policy(&[(directory.path().to_path_buf(), 12)])).unwrap();
    let base = base_arguments(directory.path(), "sandbox-unavailable", "xterm", 80, 24);
    let failed = Command::new(env!("CARGO_BIN_EXE_session-host"))
        .args(&base)
        .args(["--sandbox-policy", policy.to_str().unwrap(), "--", "/usr/bin/true"])
        .output()
        .unwrap();
    assert_eq!(failed.status.code(), Some(70));
    let fallback = Command::new(env!("CARGO_BIN_EXE_session-host"))
        .args(&base)
        .args([
            "--sandbox-policy", policy.to_str().unwrap(), "--sandbox-unavailable",
            "run-unsandboxed", "--", "/usr/bin/true",
        ])
        .output()
        .unwrap();
    assert!(fallback.status.success(), "{}", String::from_utf8_lossy(&fallback.stderr));
    let metadata = journal::read_metadata(directory.path()).unwrap();
    assert_eq!(metadata.sandbox.policy_version, Some(1));
    assert_eq!(metadata.sandbox.handled_rights, Some(131_071));
    assert_eq!(metadata.sandbox.rules.len(), 1);
    assert_eq!(metadata.sandbox.rules[0].path, directory.path().to_str().unwrap());
    assert_eq!(metadata.sandbox.rules[0].rights, ["read-file", "read-dir"]);
}

#[test]
fn hosts_a_real_tty_and_preserves_raw_output() {
    let directory = temporary_directory("raw-output");
    let mut host = HostGuard::spawn(
        directory,
        &[
            "/bin/sh",
            "-c",
            "test -t 0 && test -t 1 && test -t 2 || exit 91; \
             test \"$TERM\" = xterm-test || exit 92; \
             printf '\\033[31mraw'; printf '\\377'; printf '\\033[0m\\n'",
        ],
        "xterm-test",
        80,
        24,
    );

    let status = host.wait();
    assert!(status.success(), "session-host exited with {status}");
    let result = journal::read(host.directory(), 0).unwrap();
    let output = terminal_output(&result.events);
    assert!(contains(&output, b"\x1b[31mraw\xff\x1b[0m"));
    assert_eq!(
        result.events.first().unwrap().event_type,
        event_type::PROCESS_STARTED
    );
    assert_eq!(
        result.events.last().unwrap().event_type,
        event_type::PROCESS_EXITED
    );
    assert_eq!(
        &result.events.last().unwrap().payload[0..4],
        &0_i32.to_le_bytes()
    );
    assert_eq!(
        &result.events.last().unwrap().payload[4..8],
        &(-1_i32).to_le_bytes()
    );

    let metadata = journal::read_metadata(host.directory()).unwrap();
    assert_eq!(metadata.current_cols, 80);
    assert_eq!(metadata.current_rows, 24);
}

#[test]
fn bounds_compresses_and_replays_the_session_journal() {
    let directory = temporary_directory("bounded-journal");
    let mut host = HostGuard::spawn_with_options(
        directory,
        &[
            "--journal-segment-bytes",
            "1",
            "--journal-max-bytes",
            "1048576",
        ],
        &["/bin/sh", "-c", "printf bounded-journal-output"],
        "xterm-256color",
        80,
        24,
    );

    let status = host.wait();
    assert!(status.success(), "session-host exited with {status}");
    let (compressed_segments, active_segments) = journal_segment_numbers(host.directory());
    assert!(!compressed_segments.is_empty());
    assert_eq!(active_segments.len(), 1);

    let result = journal::read_after(host.directory(), 0).unwrap();
    assert_eq!(terminal_output(&result.events), b"bounded-journal-output");
    assert_eq!(
        result.events.first().unwrap().event_type,
        event_type::PROCESS_STARTED
    );
    assert_eq!(
        result.events.last().unwrap().event_type,
        event_type::PROCESS_EXITED
    );

}

#[test]
fn orders_control_commands_and_deduplicates_input_after_reconnect() {
    let directory = temporary_directory("control");
    let mut host = HostGuard::spawn(
        directory,
        &[
            "/bin/sh",
            "-c",
            "stty -echo; printf READY; IFS= read -r line; stty size; \
             printf 'GOT:%s\\n' \"$line\"; sleep 30",
        ],
        "xterm-256color",
        80,
        24,
    );
    wait_for_output(host.directory(), b"READY");

    let mut first = connect(host.directory());
    let status = request(&mut first, control_message::STATUS, 1, &[]);
    assert_eq!(status.message_type, control_message::STATUS_RESPONSE);
    let journal_at_status = journal::read(host.directory(), 0).unwrap();
    assert_eq!(u16_at(&status.payload[0..2]), 2);
    assert_eq!(u16_at(&status.payload[2..4]) & 3, 3);
    assert_eq!(u32_at(&status.payload[4..8]), 80);
    assert_eq!(u32_at(&status.payload[8..12]), 24);
    assert_eq!(
        u64_at(&status.payload[28..36]),
        journal_at_status.events.first().unwrap().event_id
    );
    assert_eq!(
        u64_at(&status.payload[36..44]),
        journal_at_status.events.last().unwrap().event_id
    );

    let resize = protocol::pty_resize_payload(101, 37);
    let resized = request(&mut first, control_message::RESIZE, 2, &resize);
    assert_eq!(resized.message_type, control_message::ACCEPTED);
    let resize_event_id = u64_at(&resized.payload);

    let input_id = [0x4a; 16];
    let input = protocol::pty_input_payload(input_id, b"hello\n").unwrap();
    let accepted = request(&mut first, control_message::INPUT, 3, &input);
    assert_eq!(accepted.message_type, control_message::ACCEPTED);
    let input_event_id = u64_at(&accepted.payload);
    assert!(resize_event_id < input_event_id);
    drop(first);

    let mut reconnected = connect(host.directory());
    let changed_retry = protocol::pty_input_payload(input_id, b"second\n").unwrap();
    let duplicate = request(&mut reconnected, control_message::INPUT, 4, &changed_retry);
    assert_eq!(duplicate.message_type, control_message::DUPLICATE);
    assert_eq!(u64_at(&duplicate.payload), input_event_id);

    wait_for_output(host.directory(), b"GOT:hello");
    let terminate = [1_u8, 0, 0, 0, 0, 0, 0, 0];
    let terminated = request(&mut reconnected, control_message::TERMINATE, 5, &terminate);
    assert_eq!(terminated.message_type, control_message::ACCEPTED);
    drop(reconnected);

    let status = host.wait();
    assert!(status.success(), "session-host exited with {status}");
    let result = journal::read(host.directory(), 0).unwrap();
    let inputs: Vec<_> = result
        .events
        .iter()
        .filter(|event| event.event_type == event_type::PTY_INPUT)
        .collect();
    assert_eq!(inputs.len(), 1);
    assert_eq!(inputs[0].payload, input);
    let resize_event = result
        .events
        .iter()
        .find(|event| event.event_type == event_type::PTY_RESIZE)
        .unwrap();
    assert_eq!(resize_event.payload, resize);
    assert!(resize_event.event_id < inputs[0].event_id);
    assert!(
        result
            .events
            .iter()
            .any(|event| event.event_type == event_type::SIGNAL)
    );

    let output = terminal_output(&result.events);
    assert!(contains(&output, b"37 101"));
    assert!(contains(&output, b"GOT:hello"));
    assert!(!contains(&output, b"GOT:second"));
    let metadata = journal::read_metadata(host.directory()).unwrap();
    assert_eq!(metadata.current_cols, 101);
    assert_eq!(metadata.current_rows, 37);
}

#[test]
fn leaves_metadata_unchanged_across_output_input_and_signal_events() {
    let directory = temporary_directory("stable-metadata");
    let mut host = HostGuard::spawn(
        directory,
        &[
            "/bin/sh",
            "-c",
            "stty -echo; printf READY; IFS= read -r line; printf 'GOT:%s\\n' \"$line\"; sleep 30",
        ],
        "xterm-256color",
        80,
        24,
    );
    wait_for_output(host.directory(), b"READY");

    let mut stream = connect(host.directory());
    let status = request(&mut stream, control_message::STATUS, 1, &[]);
    assert_eq!(status.message_type, control_message::STATUS_RESPONSE);
    let metadata_before = fs::read(host.directory().join("metadata")).unwrap();

    let input = protocol::pty_input_payload([0x6d; 16], b"hello\n").unwrap();
    let accepted = request(&mut stream, control_message::INPUT, 2, &input);
    assert_eq!(accepted.message_type, control_message::ACCEPTED);
    wait_for_output(host.directory(), b"GOT:hello");

    let signal = host::signal_payload(1, -1);
    let signalled = request(&mut stream, control_message::SIGNAL, 3, &signal);
    assert_eq!(signalled.message_type, control_message::ACCEPTED);
    drop(stream);

    let process_status = host.wait_with_timeout(Duration::from_secs(2));
    assert!(process_status.success(), "session-host exited with {process_status}");
    let metadata_after = fs::read(host.directory().join("metadata")).unwrap();
    assert_eq!(metadata_after, metadata_before);

    let result = journal::read(host.directory(), 0).unwrap();
    assert!(result.events.iter().any(|event| event.event_type == event_type::PTY_INPUT));
    assert!(result.events.iter().any(|event| event.event_type == event_type::PTY_OUTPUT));
    assert!(result.events.iter().any(|event| event.event_type == event_type::SIGNAL));
}

#[test]
fn keeps_detached_pty_descendant_controllable_after_its_leader_exits() {
    let directory = temporary_directory("orphaned-group");
    let mut host = HostGuard::spawn(
        directory,
        &[
            "/usr/bin/perl",
            "-MPOSIX",
            "-e",
            concat!(
                "pipe(my $ready_read, my $ready_write) or die; ",
                "defined(my $pid = fork) or die; ",
                "if ($pid) { close $ready_write; ",
                "sysread($ready_read, my $ready, 1) == 1 or die; exit 0; } ",
                "close $ready_read; POSIX::setsid() >= 0 or die; ",
                "syswrite($ready_write, q(1), 1) == 1 or die; close $ready_write; ",
                "$| = 1; print qq(READY); sleep 30",
            ),
        ],
        "xterm-256color",
        80,
        24,
    );
    wait_for_output(host.directory(), b"READY");
    let metadata = journal::read_metadata(host.directory()).unwrap();
    let leader_pid = i32::try_from(metadata.child_pid.unwrap()).unwrap();
    wait_for_process_exit(leader_pid);

    let mut stream = connect(host.directory());
    let status = request(&mut stream, control_message::STATUS, 1, &[]);
    assert_eq!(status.message_type, control_message::STATUS_RESPONSE);
    assert_eq!(u16_at(&status.payload[2..4]) & 3, 3);

    let terminate = [1_u8, 0, 0, 0, 0, 0, 0, 0];
    let terminated = request(&mut stream, control_message::TERMINATE, 2, &terminate);
    assert_eq!(terminated.message_type, control_message::ACCEPTED);
    drop(stream);
    assert!(host.wait().success());
}

#[test]
fn sends_interactive_signals_to_the_foreground_process_group() {
    let directory = temporary_directory("foreground-signal");
    let mut host = HostGuard::spawn(
        directory,
        &[
            "/bin/sh",
            "-c",
            "set -m; printf READY; sleep 30; printf FINISHED",
        ],
        "xterm-256color",
        80,
        24,
    );
    wait_for_output(host.directory(), b"READY");

    let mut stream = connect(host.directory());
    let signal = host::signal_payload(1, -1);
    let response = request(&mut stream, control_message::SIGNAL, 1, &signal);
    assert_eq!(response.message_type, control_message::ACCEPTED);
    drop(stream);

    let status = host.wait_with_timeout(Duration::from_secs(2));
    assert!(status.success(), "session-host exited with {status}");
}

#[test]
fn blocked_pty_input_does_not_block_status_or_termination() {
    let directory = temporary_directory("blocked-input");
    let mut host = HostGuard::spawn(
        directory,
        &["/bin/sh", "-c", "printf READY; sleep 30"],
        "xterm-256color",
        80,
        24,
    );
    wait_for_output(host.directory(), b"READY");

    let input_directory = host.directory().to_owned();
    let input_thread = thread::spawn(move || {
        let mut stream = connect(&input_directory);
        let input = protocol::pty_input_payload(
            [0x62; 16],
            &vec![b'x'; MAX_PAYLOAD_LENGTH - 16],
        )
        .unwrap();
        request(&mut stream, control_message::INPUT, 1, &input)
    });
    wait_for_event(host.directory(), event_type::PTY_INPUT);

    let mut stream = connect(host.directory());
    stream.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
    let status = request(&mut stream, control_message::STATUS, 2, &[]);
    assert_eq!(status.message_type, control_message::STATUS_RESPONSE);

    let terminate = [1_u8, 0, 0, 0, 0, 0, 0, 0];
    let terminated = request(&mut stream, control_message::TERMINATE, 3, &terminate);
    assert_eq!(terminated.message_type, control_message::ACCEPTED);
    drop(stream);
    let _ = input_thread.join().unwrap();
    assert!(host.wait().success());
}

#[test]
fn restores_default_sigpipe_disposition_in_child() {
    let directory = temporary_directory("sigpipe");
    let mut host = HostGuard::spawn(
        directory,
        &["/bin/sh", "-c", "kill -PIPE $$; printf SURVIVED"],
        "xterm-256color",
        80,
        24,
    );

    assert!(host.wait().success());
    let result = journal::read(host.directory(), 0).unwrap();
    let exited = result
        .events
        .iter()
        .find(|event| event.event_type == event_type::PROCESS_EXITED)
        .unwrap();
    assert_eq!(&exited.payload[0..4], &i32::MIN.to_le_bytes());
    assert_eq!(&exited.payload[4..8], &(-1_i32).to_le_bytes());
    assert!(!contains(&terminal_output(&result.events), b"SURVIVED"));
}

#[test]
fn remains_available_after_the_launching_process_exits() {
    let directory = DirectoryGuard::new(temporary_directory("parent-exit"));
    let binary = env!("CARGO_BIN_EXE_session-host");
    let output = Command::new("/bin/sh")
        .arg("-c")
        .arg("\"$@\" </dev/null >/dev/null 2>/dev/null & echo $!")
        .arg("launcher")
        .arg(binary)
        .args(base_arguments(
            directory.path(),
            "parent-exit",
            "xterm-256color",
            80,
            24,
        ))
        .arg("--")
        .args([
            "/bin/sh",
            "-c",
            "stty -echo; printf READY; IFS= read -r line; printf 'SURVIVED:%s\\n' \"$line\"",
        ])
        .output()
        .unwrap();
    assert!(output.status.success());
    let host_pid: i32 = String::from_utf8(output.stdout)
        .unwrap()
        .trim()
        .parse()
        .unwrap();
    let mut process_guard = ProcessGuard(Some(host_pid));

    wait_for_output(directory.path(), b"READY");
    let mut stream = connect(directory.path());
    let input = protocol::pty_input_payload([0x73; 16], b"yes\n").unwrap();
    let response = request(&mut stream, control_message::INPUT, 1, &input);
    assert_eq!(response.message_type, control_message::ACCEPTED);
    drop(stream);

    wait_for_event(directory.path(), event_type::PROCESS_EXITED);
    let result = journal::read(directory.path(), 0).unwrap();
    assert!(contains(&terminal_output(&result.events), b"SURVIVED:yes"));
    wait_for_process_exit(host_pid);
    process_guard.0 = None;
}

#[test]
fn rejects_a_second_host_without_unlinking_the_live_endpoint() {
    let directory = temporary_directory("already-active");
    let mut host = HostGuard::spawn(
        directory,
        &["/bin/sh", "-c", "printf READY; sleep 30"],
        "xterm-256color",
        80,
        24,
    );
    wait_for_output(host.directory(), b"READY");

    let duplicate = Command::new(env!("CARGO_BIN_EXE_session-host"))
        .args(base_arguments(
            host.directory(),
            "duplicate-session",
            "xterm-256color",
            80,
            24,
        ))
        .arg("--")
        .arg("/bin/true")
        .output()
        .unwrap();
    assert_eq!(duplicate.status.code(), Some(70));
    assert!(contains(
        &duplicate.stderr,
        b"another session host is listening"
    ));

    let mut stream = connect(host.directory());
    let status = request(&mut stream, control_message::STATUS, 1, &[]);
    assert_eq!(status.message_type, control_message::STATUS_RESPONSE);
    let terminate = [1_u8, 0, 0, 0, 0, 0, 0, 0];
    let terminated = request(&mut stream, control_message::TERMINATE, 2, &terminate);
    assert_eq!(terminated.message_type, control_message::ACCEPTED);
    drop(stream);
    assert!(host.wait().success());
}

struct HostGuard {
    child: Option<Child>,
    directory: DirectoryGuard,
}

impl HostGuard {
    fn spawn(directory: PathBuf, child_command: &[&str], term: &str, cols: u16, rows: u16) -> Self {
        Self::spawn_with_options(directory, &[], child_command, term, cols, rows)
    }

    fn spawn_with_options(
        directory: PathBuf,
        options: &[&str],
        child_command: &[&str],
        term: &str,
        cols: u16,
        rows: u16,
    ) -> Self {
        let directory = DirectoryGuard::new(directory);
        let child = Command::new(env!("CARGO_BIN_EXE_session-host"))
            .args(base_arguments(
                directory.path(),
                "test-session",
                term,
                cols,
                rows,
            ))
            .args(options)
            .arg("--")
            .args(child_command)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::inherit())
            .spawn()
            .unwrap();
        Self {
            child: Some(child),
            directory,
        }
    }

    #[cfg(target_os = "linux")]
    fn spawn_with_policy(directory: PathBuf, policy: &Path, child_command: &[&str]) -> Self {
        let directory = DirectoryGuard::new(directory);
        let child = Command::new(env!("CARGO_BIN_EXE_session-host"))
            .args(base_arguments(
                directory.path(),
                "landlock-boundary",
                "xterm-256color",
                80,
                24,
            ))
            .args(["--sandbox-policy", policy.to_str().unwrap(), "--"])
            .args(child_command)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::inherit())
            .spawn()
            .unwrap();
        Self {
            child: Some(child),
            directory,
        }
    }

    fn directory(&self) -> &Path {
        self.directory.path()
    }

    fn wait(&mut self) -> ExitStatus {
        self.child.as_mut().unwrap().wait().unwrap()
    }

    fn wait_with_timeout(&mut self, timeout: Duration) -> ExitStatus {
        let deadline = Instant::now() + timeout;
        loop {
            if let Some(status) = self.child.as_mut().unwrap().try_wait().unwrap() {
                return status;
            }
            assert!(Instant::now() < deadline, "timed out waiting for session-host");
            thread::sleep(Duration::from_millis(10));
        }
    }
}

impl Drop for HostGuard {
    fn drop(&mut self) {
        let Some(child) = self.child.as_mut() else {
            return;
        };
        if child.try_wait().ok().flatten().is_some() {
            return;
        }
        if let Ok(mut stream) = UnixStream::connect(self.directory.path().join("control.sock")) {
            let terminate = [1_u8, 0, 0, 0, 0, 0, 0, 0];
            let _ = request(
                &mut stream,
                control_message::TERMINATE,
                u64::MAX,
                &terminate,
            );
            thread::sleep(Duration::from_millis(50));
        }
        if child.try_wait().ok().flatten().is_none() {
            kill_recorded_child(self.directory.path());
            let _ = child.kill();
        }
        let _ = child.wait();
    }
}

struct DirectoryGuard(PathBuf);

impl DirectoryGuard {
    fn new(path: PathBuf) -> Self {
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

struct ProcessGuard(Option<i32>);

impl Drop for ProcessGuard {
    fn drop(&mut self) {
        if let Some(pid) = self.0 {
            unsafe {
                libc::kill(pid, libc::SIGKILL);
            }
        }
    }
}

fn base_arguments(
    directory: &Path,
    session_id: &str,
    term: &str,
    cols: u16,
    rows: u16,
) -> Vec<String> {
    vec![
        "--session-id".to_owned(),
        session_id.to_owned(),
        "--session-dir".to_owned(),
        directory.display().to_string(),
        "--cwd".to_owned(),
        "/tmp".to_owned(),
        "--cols".to_owned(),
        cols.to_string(),
        "--rows".to_owned(),
        rows.to_string(),
        "--term".to_owned(),
        term.to_owned(),
    ]
}

fn encode_policy(rules: &[(PathBuf, u64)]) -> Vec<u8> {
    let mut bytes = Vec::new();
    cbor_argument(&mut bytes, 4, 3);
    cbor_argument(&mut bytes, 0, 1);
    cbor_argument(&mut bytes, 0, 131_071);
    cbor_argument(&mut bytes, 4, rules.len() as u64);
    for (path, rights) in rules {
        cbor_argument(&mut bytes, 4, 2);
        let path = path.to_str().unwrap().as_bytes();
        cbor_argument(&mut bytes, 3, path.len() as u64);
        bytes.extend_from_slice(path);
        cbor_argument(&mut bytes, 0, *rights);
    }
    bytes
}

fn cbor_argument(output: &mut Vec<u8>, major: u8, value: u64) {
    let prefix = major << 5;
    match value {
        0..=23 => output.push(prefix | value as u8),
        24..=0xff => output.extend_from_slice(&[prefix | 24, value as u8]),
        0x100..=0xffff => {
            output.push(prefix | 25);
            output.extend_from_slice(&(value as u16).to_be_bytes());
        }
        0x1_0000..=0xffff_ffff => {
            output.push(prefix | 26);
            output.extend_from_slice(&(value as u32).to_be_bytes());
        }
        _ => {
            output.push(prefix | 27);
            output.extend_from_slice(&value.to_be_bytes());
        }
    }
}

fn connect(directory: &Path) -> UnixStream {
    let endpoint = directory.join("control.sock");
    let deadline = Instant::now() + TIMEOUT;
    loop {
        match UnixStream::connect(&endpoint) {
            Ok(stream) => return stream,
            Err(error) if Instant::now() < deadline => {
                if !matches!(
                    error.kind(),
                    io::ErrorKind::NotFound
                        | io::ErrorKind::ConnectionRefused
                        | io::ErrorKind::ConnectionReset
                ) {
                    panic!("cannot connect to {}: {error}", endpoint.display());
                }
                thread::sleep(Duration::from_millis(10));
            }
            Err(error) => panic!("timed out connecting to {}: {error}", endpoint.display()),
        }
    }
}

fn request(
    stream: &mut UnixStream,
    message_type: u16,
    request_id: u64,
    payload: &[u8],
) -> OwnedControlFrame {
    host::write_control_frame(stream, message_type, request_id, payload).unwrap_or_else(|error| {
        panic!("cannot write control message {message_type:#06x} request {request_id}: {error}")
    });
    host::read_control_frame(stream)
        .unwrap_or_else(|error| {
            panic!("cannot read control message {message_type:#06x} request {request_id}: {error}")
        })
        .unwrap_or_else(|| {
            panic!("control connection closed for message {message_type:#06x} request {request_id}")
        })
}

fn wait_for_output(directory: &Path, expected: &[u8]) {
    let deadline = Instant::now() + TIMEOUT;
    loop {
        if let Ok(result) = journal::read(directory, 0)
            && contains(&terminal_output(&result.events), expected)
        {
            return;
        }
        assert!(
            Instant::now() < deadline,
            "timed out waiting for terminal output"
        );
        thread::sleep(Duration::from_millis(10));
    }
}

fn wait_for_event(directory: &Path, expected: u16) {
    let deadline = Instant::now() + TIMEOUT;
    loop {
        if let Ok(result) = journal::read(directory, 0)
            && result.events.iter().any(|event| event.event_type == expected)
        {
            return;
        }
        assert!(
            Instant::now() < deadline,
            "timed out waiting for journal event {expected:#06x}"
        );
        thread::sleep(Duration::from_millis(10));
    }
}

fn wait_for_process_exit(pid: i32) {
    let deadline = Instant::now() + TIMEOUT;
    loop {
        let result = unsafe { libc::kill(pid, 0) };
        if result != 0 && io::Error::last_os_error().raw_os_error() == Some(libc::ESRCH) {
            return;
        }
        assert!(
            Instant::now() < deadline,
            "timed out waiting for host process exit"
        );
        thread::sleep(Duration::from_millis(10));
    }
}

fn terminal_output(events: &[journal::JournalEvent]) -> Vec<u8> {
    let mut output = Vec::new();
    for event in events {
        if event.event_type == event_type::PTY_OUTPUT {
            output.extend_from_slice(&event.payload);
        }
    }
    output
}

fn journal_segment_numbers(directory: &Path) -> (Vec<u64>, Vec<u64>) {
    let mut compressed = Vec::new();
    let mut active = Vec::new();
    for entry in fs::read_dir(directory).unwrap() {
        let entry = entry.unwrap();
        let name = entry.file_name();
        let name = name.to_str().unwrap();
        if let Some(number) = name.strip_suffix(".cbor.zst") {
            compressed.push(number.parse().unwrap());
        } else if let Some(number) = name.strip_suffix(".cbor") {
            active.push(number.parse().unwrap());
        }
    }
    compressed.sort_unstable();
    active.sort_unstable();
    (compressed, active)
}

fn contains(haystack: &[u8], needle: &[u8]) -> bool {
    haystack
        .windows(needle.len())
        .any(|window| window == needle)
}

fn kill_recorded_child(directory: &Path) {
    let Ok(Metadata {
        child_pid: Some(pid),
        ..
    }) = journal::read_metadata(directory)
    else {
        return;
    };
    if let Ok(pid) = i32::try_from(pid) {
        unsafe {
            libc::kill(-pid, libc::SIGKILL);
        }
    }
}

fn temporary_directory(name: &str) -> PathBuf {
    let sequence = NEXT_DIRECTORY.fetch_add(1, Ordering::Relaxed);
    let micros = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_micros();
    PathBuf::from("/tmp").join(format!(
        "osh-{name}-{}-{sequence}-{micros}",
        std::process::id()
    ))
}

fn u16_at(bytes: &[u8]) -> u16 {
    u16::from_le_bytes(bytes.try_into().unwrap())
}

fn u32_at(bytes: &[u8]) -> u32 {
    u32::from_le_bytes(bytes.try_into().unwrap())
}

fn u64_at(bytes: &[u8]) -> u64 {
    u64::from_le_bytes(bytes.try_into().unwrap())
}
