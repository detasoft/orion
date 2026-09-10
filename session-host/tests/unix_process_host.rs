#![cfg(unix)]

mod support;

use std::fs;
use std::io::{self, Write};
#[cfg(target_os = "linux")]
use std::os::unix::ffi::OsStrExt;
use std::os::unix::net::UnixStream;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, ExitStatus, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use orion_session_host::host::{self, ERROR_INVALID_REQUEST, OwnedControlFrame};
use orion_session_host::journal::{self, Metadata};
use orion_session_host::journal_acknowledgement::STATE_FILE_NAME;
use orion_session_host::protocol::{self, ControlFrame, control_message, event_type};
use support::journal::{self as journal_reader, JournalEvent};

const TIMEOUT: Duration = Duration::from_secs(10);
static NEXT_DIRECTORY: AtomicU64 = AtomicU64::new(0);

#[test]
fn lists_owned_processes_and_signals_tokens_after_root_exit() {
    let directory = temporary_directory("list-processes");
    let child_file = directory.join("child.pid");
    let mut host = HostGuard::spawn(directory, &[
        "/usr/bin/perl", "-e",
        concat!("$SIG{HUP}=q(IGNORE); defined(my $child = fork) or die; ",
            "if (!$child) { open my $f, q(>), $ARGV[0] or die; print {$f} $$; close $f; } ",
            "while (1) { sleep 30; }"),
        child_file.to_str().unwrap(),
    ], "xterm-256color", 80, 24);
    let child = wait_for_pid_file(&child_file) as u64;
    let mut stream = connect(host.directory());
    let processes = listed_processes(&mut stream, u64::MAX - 1);
    let root = *processes.iter().find(|process| process.original_root).unwrap();
    let descendant = *processes.iter().find(|process| process.pid == child).unwrap();
    assert_ne!(root.token, descendant.token);
    assert_eq!(listed_processes(&mut stream, 0), processes);
    assert_eq!(journal_reader::read(host.directory(), 0).unwrap().events.iter()
        .filter(|event| event.event_type == event_type::COMMAND_RESULT).count(), 0);
    let malformed = request(&mut stream, control_message::LIST_PROCESSES, 3, &[0]);
    assert_eq!(malformed.message_type, control_message::ERROR);
    let schema = request_with_schema(&mut stream, control_message::LIST_PROCESSES, 2, 4, &[]);
    assert_eq!(schema.message_type, control_message::ERROR);
    for (schema, effect) in [(4, vec![1, 0, 0, 0, 255, 255, 255, 255]),
        (3, vec![1, 0, 0, 0, 255, 255, 255, 255, 1, 0, 0, 0, 0, 0, 0, 0])]
    {
        let payload = protocol::encode_operation_control_payload(control_message::SIGNAL,
            protocol::OperationSource::Server, Some(b"invalid-schema"), &effect).unwrap();
        assert_received_error(&request_with_schema(&mut stream, control_message::SIGNAL,
            schema, 1000, &payload), 1000, ERROR_INVALID_REQUEST);
    }

    addressed_signal(&mut stream, 1, descendant.token, 0xffff, libc::SIGCONT);
    wait_for_command_result(host.directory(), 1);
    addressed_signal(&mut stream, 2, u64::MAX, 3, -1);
    wait_for_command_result(host.directory(), 2);
    assert!(listed_processes(&mut stream, 5).contains(&descendant));

    addressed_signal(&mut stream, 3, root.token, 3, -1);
    wait_for_command_result(host.directory(), 3);
    let deadline = Instant::now() + TIMEOUT;
    loop {
        let current = listed_processes(&mut stream, 6);
        if !current.contains(&root) {
            assert!(current.contains(&descendant));
            assert!(current.iter().all(|process| !process.original_root));
            break;
        }
        assert!(Instant::now() < deadline, "root token was not retired");
        thread::sleep(Duration::from_millis(10));
    }
    addressed_signal(&mut stream, 4, root.token, 3, -1);
    wait_for_command_result(host.directory(), 4);
    let events = journal_reader::read(host.directory(), 0).unwrap().events;
    for sequence in [2, 4] {
        let event = events.iter().find(|event| event.event_type == event_type::COMMAND_RESULT
            && u64_at(&event.payload[2..10]) == sequence).unwrap();
        let envelope_length = u32_at(&event.payload[10..14]) as usize;
        assert_eq!(event.payload[14 + envelope_length], 2);
    }
    assert_eq!(events.iter().filter(|event| event.event_type == event_type::SIGNAL).count(), 2);
    assert!(host.child.as_mut().unwrap().try_wait().unwrap().is_none());

    let mut concurrent = connect(host.directory());
    concurrent.set_read_timeout(Some(TIMEOUT)).unwrap();
    let (ready, started) = std::sync::mpsc::channel();
    let listing = thread::spawn(move || {
        assert!(listed_processes(&mut concurrent, 100).contains(&descendant));
        ready.send(()).unwrap();
        let bytes = protocol::encode_control_frame(ControlFrame {
            message_type: control_message::LIST_PROCESSES, payload_schema_version: 1,
            flags: 0, sequence: 100, payload: &[],
        }).unwrap();
        for _ in 0..100 {
            if concurrent.write_all(&bytes).is_err() { break; }
            match host::read_control_frame(&mut concurrent) {
                Ok(Some(response)) => {
                    assert_eq!(response.message_type, control_message::LIST_PROCESSES_RESPONSE);
                }
                Ok(None) | Err(_) => break,
            }
        }
    });
    started.recv_timeout(TIMEOUT).unwrap();
    let terminated = operation_request(&mut stream, control_message::TERMINATE, 5,
        b"terminate", &[1, 0, 0, 0]);
    assert_received(&terminated, 5);
    listing.join().unwrap();
    drop(stream);
    assert!(host.wait().success());
    wait_for_process_exit(child as i32);
}

fn listed_processes(stream: &mut UnixStream, sequence: u64) -> Vec<protocol::ListedProcess> {
    let response = request(stream, control_message::LIST_PROCESSES, sequence, &[]);
    assert_eq!(response.message_type, control_message::LIST_PROCESSES_RESPONSE);
    assert_eq!(response.sequence, sequence);
    assert_eq!(response.payload_schema_version, 1);
    let count = u32_at(&response.payload[..4]) as usize;
    assert_eq!(response.payload.len(), 4 + 24 * count);
    response.payload[4..].chunks_exact(24).map(|entry| {
        assert_eq!(u32_at(&entry[20..24]), 0);
        assert!(u32_at(&entry[16..20]) <= 1);
        protocol::ListedProcess {
            token: u64_at(&entry[..8]), pid: u64_at(&entry[8..16]),
            original_root: u32_at(&entry[16..20]) == 1,
        }
    }).collect()
}

fn addressed_signal(stream: &mut UnixStream, sequence: u64, token: u64, kind: u16, signal: i32) {
    let mut effect = Vec::new();
    effect.extend_from_slice(&kind.to_le_bytes());
    effect.extend_from_slice(&0_u16.to_le_bytes());
    effect.extend_from_slice(&signal.to_le_bytes());
    effect.extend_from_slice(&token.to_le_bytes());
    let payload = protocol::encode_operation_control_payload(control_message::SIGNAL,
        protocol::OperationSource::Server, Some(b"addressed"), &effect).unwrap();
    assert_received(&request_with_schema(stream, control_message::SIGNAL, 4, sequence, &payload), sequence);
}

#[test]
fn failed_exec_records_the_authoritative_start_failure() {
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
    let events = journal_reader::read(directory.path(), 0).unwrap().events;
    assert_start_failure(&events, "child command exec failed");
}

#[test]
fn missing_working_directory_records_the_authoritative_start_failure() {
    let directory = DirectoryGuard::new(temporary_directory("missing-working-directory"));
    let missing_cwd = directory.path().join("missing-cwd");
    let output = Command::new(env!("CARGO_BIN_EXE_session-host"))
        .args(base_arguments_with_cwd(
            directory.path(),
            "missing-working-directory",
            &missing_cwd,
            "xterm-256color",
            80,
            24,
        ))
        .args(["--", "/usr/bin/true"])
        .output()
        .unwrap();

    assert_eq!(output.status.code(), Some(70));
    let events = journal_reader::read(directory.path(), 0).unwrap().events;
    assert_start_failure(&events, "child failed to change working directory");
}

#[cfg(target_os = "linux")]
#[test]
fn unavailable_cgroup_warns_exactly_once_before_process_started() {
    let directory = temporary_directory("cgroup-fallback-warning");
    let mut host = HostGuard::spawn(
        directory,
        &["/bin/sh", "-c", "printf fallback-ready"],
        "xterm-256color",
        80,
        24,
    );

    let status = host.wait();
    assert!(status.success(), "session-host exited with {status}");
    let events = journal_reader::read(host.directory(), 0).unwrap().events;
    let warnings = events
        .iter()
        .enumerate()
        .filter(|(_, event)| event.event_type == event_type::HOST_WARNING)
        .collect::<Vec<_>>();
    if warnings.is_empty() {
        eprintln!("cgroup fallback assertion skipped: writable delegated cgroup v2 is available");
        return;
    }

    assert_eq!(warnings.len(), 1);
    let (warning_index, warning) = warnings[0];
    assert_eq!(u16_at(&warning.payload[0..2]), protocol::host_warning::CGROUP_FALLBACK);
    let reason = std::str::from_utf8(&warning.payload[2..]).unwrap();
    assert!(!reason.is_empty());
    assert!(reason.len() <= 4096);
    let started_index = events
        .iter()
        .position(|event| event.event_type == event_type::PROCESS_STARTED)
        .unwrap();
    assert!(warning_index < started_index);
    assert_eq!(terminal_output(&events), b"fallback-ready");
}

#[test]
fn post_journal_initialization_failure_records_the_authoritative_outcome() {
    let directory = DirectoryGuard::new(temporary_directory("post-journal-failure"));
    fs::create_dir_all(directory.path()).unwrap();
    fs::write(directory.path().join(STATE_FILE_NAME), b"not-json").unwrap();
    let output = Command::new(env!("CARGO_BIN_EXE_session-host"))
        .args(base_arguments(
            directory.path(),
            "post-journal-failure",
            "xterm-256color",
            80,
            24,
        ))
        .args(["--", "/usr/bin/true"])
        .output()
        .unwrap();

    assert_eq!(output.status.code(), Some(70));
    let events = journal_reader::read(directory.path(), 0).unwrap().events;
    assert_start_failure(&events, "cannot decode state");
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
        (
            fs::canonicalize(workspace.join("writable")).unwrap(),
            16_390,
        ),
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
        assert_unsupported_landlock_falls_back(&directory, &policy);
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
    assert_eq!(u16_at(&status.payload[2..4]) & 4, 4);
    let terminate = [1_u8, 0, 0, 0];
    send_operation(
        &mut stream,
        control_message::TERMINATE,
        1,
        b"server-envelope-sandbox-terminate",
        &terminate,
    );
    drop(stream);
    assert!(host.wait().success());
}

#[test]
fn invalid_grants_remain_fatal_when_landlock_is_unavailable() {
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
fn assert_unsupported_landlock_falls_back(directory: &Path, policy: &Path) {
    let base = base_arguments(directory, "landlock-unsupported", "xterm-256color", 80, 24);
    let output = Command::new(env!("CARGO_BIN_EXE_session-host"))
        .args(&base)
        .args([
            "--sandbox-policy",
            policy.to_str().unwrap(),
            "--",
            "/bin/sh",
            "-c",
            "exit 0",
        ])
        .output()
        .unwrap();
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert!(String::from_utf8_lossy(&output.stderr).contains(
        "warning: Landlock ABI 9 is unavailable; running without filesystem restrictions"
    ));
    let metadata = journal::read_metadata(directory).unwrap();
    assert!(metadata.sandbox.requested);
    assert_eq!(
        metadata.sandbox.enforcement,
        journal::SandboxEnforcement::None
    );
    assert_eq!(
        metadata.sandbox.unavailable_policy,
        journal::SandboxUnavailablePolicy::RunUnsandboxed
    );
}

#[cfg(not(target_os = "linux"))]
#[test]
fn requested_landlock_policy_falls_back_when_landlock_is_unavailable() {
    let directory = DirectoryGuard::new(temporary_directory("sandbox-unavailable"));
    fs::create_dir_all(directory.path()).unwrap();
    let policy = directory.path().join("policy.cbor");
    fs::write(
        &policy,
        encode_policy(&[(directory.path().to_path_buf(), 12)]),
    )
    .unwrap();
    let output = Command::new(env!("CARGO_BIN_EXE_session-host"))
        .args(base_arguments(
            directory.path(),
            "sandbox-unavailable",
            "xterm",
            80,
            24,
        ))
        .args([
            "--sandbox-policy",
            policy.to_str().unwrap(),
            "--",
            "/usr/bin/true",
        ])
        .output()
        .unwrap();
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert!(String::from_utf8_lossy(&output.stderr).contains(
        "warning: Landlock ABI 9 is unavailable; running without filesystem restrictions"
    ));
    let metadata = journal::read_metadata(directory.path()).unwrap();
    assert!(metadata.sandbox.requested);
    assert_eq!(
        metadata.sandbox.enforcement,
        journal::SandboxEnforcement::None
    );
    assert_eq!(
        metadata.sandbox.unavailable_policy,
        journal::SandboxUnavailablePolicy::RunUnsandboxed
    );
    assert_eq!(metadata.sandbox.policy_version, Some(1));
    assert_eq!(metadata.sandbox.handled_rights, Some(131_071));
    assert_eq!(metadata.sandbox.rules.len(), 1);
    assert_eq!(
        metadata.sandbox.rules[0].path,
        directory.path().to_str().unwrap()
    );
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
    let result = journal_reader::read(host.directory(), 0).unwrap();
    let output = terminal_output(&result.events);
    assert!(contains(&output, b"\x1b[31mraw\xff\x1b[0m"));
    assert_start_prefix(&result.events, event_type::PROCESS_STARTED);
    assert_eq!(
        result
            .events
            .iter()
            .filter(|event| matches!(
                event.event_type,
                event_type::PROCESS_STARTED | event_type::SESSION_START_FAILED
            ))
            .count(),
        1
    );
    assert_eq!(
        result.events.last().unwrap().event_type,
        event_type::PROCESS_EXITED
    );
    assert_eq!(
        result
            .events
            .iter()
            .filter(|event| event.event_type == event_type::PROCESS_EXITED)
            .count(),
        1
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

    let result = journal_reader::read_after(host.directory(), 0).unwrap();
    assert_eq!(terminal_output(&result.events), b"bounded-journal-output");
    assert_start_prefix(&result.events, event_type::PROCESS_STARTED);
    assert_eq!(
        result.events.last().unwrap().event_type,
        event_type::PROCESS_EXITED
    );
}

#[test]
fn orders_controls_and_rejects_duplicate_sequences_after_reconnect() {
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
    assert_eq!(status.sequence, 1);
    let journal_at_status = journal_reader::read(host.directory(), 0).unwrap();
    assert_eq!(u16_at(&status.payload[0..2]), 2);
    assert_eq!(u16_at(&status.payload[2..4]) & 3, 3);
    assert_eq!(u16_at(&status.payload[2..4]) & 4, 0);
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
    send_operation(
        &mut first,
        control_message::RESIZE,
        1,
        b"server-envelope-resize",
        &resize,
    );

    let input_id = [0x4a; 16];
    let input = protocol::pty_input_payload(input_id, b"hello\n").unwrap();
    send_operation(
        &mut first,
        control_message::INPUT,
        2,
        b"server-envelope-input",
        &input,
    );
    wait_for_event_count(host.directory(), event_type::COMMAND_RESULT, 2);
    drop(first);

    let mut reconnected = connect(host.directory());
    let duplicate = operation_request(
        &mut reconnected,
        control_message::INPUT,
        2,
        b"server-envelope-input",
        &input,
    );
    assert_received_error(&duplicate, 2, ERROR_INVALID_REQUEST);

    let changed_retry = protocol::pty_input_payload(input_id, b"second\n").unwrap();
    let conflict = operation_request(
        &mut reconnected,
        control_message::INPUT,
        2,
        b"server-envelope-input",
        &changed_retry,
    );
    assert_received_error(&conflict, 2, ERROR_INVALID_REQUEST);
    let envelope_conflict = operation_request(
        &mut reconnected,
        control_message::INPUT,
        2,
        b"server-envelope-input-changed",
        &input,
    );
    assert_received_error(&envelope_conflict, 2, ERROR_INVALID_REQUEST);

    wait_for_output(host.directory(), b"GOT:hello");
    let terminate = [1_u8, 0, 0, 0];
    send_operation(
        &mut reconnected,
        control_message::TERMINATE,
        5,
        b"server-envelope-terminate",
        &terminate,
    );
    drop(reconnected);

    let status = host.wait();
    assert!(status.success(), "session-host exited with {status}");
    let result = journal_reader::read(host.directory(), 0).unwrap();
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
    assert_command_events(&result.events, event_type::PTY_RESIZE, 1);
    assert_command_events(&result.events, event_type::PTY_INPUT, 2);
    assert_command_events(&result.events, event_type::SIGNAL, 5);
    assert_eq!(
        result
            .events
            .iter()
            .filter(|event| event.event_type == event_type::COMMAND_RESULT)
            .count(),
        3,
    );
    assert_eq!(
        result
            .events
            .iter()
            .filter(|event| event.event_type == event_type::SIGNAL)
            .count(),
        1,
    );
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
fn interleaves_sources_and_executes_every_repeated_manual_delivery() {
    let directory = temporary_directory("source-aware-controls");
    let mut host = HostGuard::spawn(
        directory,
        &["/bin/sh", "-c", "printf READY; sleep 30"],
        "xterm-256color",
        80,
        24,
    );
    wait_for_output(host.directory(), b"READY");

    let mut first = connect(host.directory());
    let operations = [
        (protocol::OperationSource::Server, 42, 101, 31),
        (protocol::OperationSource::Manual, 1, 102, 32),
        (protocol::OperationSource::Manual, u64::MAX - 1, 103, 33),
        (protocol::OperationSource::Server, 43, 104, 34),
        (protocol::OperationSource::Manual, 2, 105, 35),
        (protocol::OperationSource::Manual, 1, 106, 36),
    ];
    for (source, sequence, columns, rows) in operations {
        let effect = protocol::pty_resize_payload(columns, rows);
        send_operation_from(
            &mut first,
            control_message::RESIZE,
            sequence,
            source,
            (source == protocol::OperationSource::Server).then_some(b"server-envelope".as_slice()),
            &effect,
        );
    }

    let stale = operation_request(
        &mut first,
        control_message::RESIZE,
        43,
        b"server-envelope",
        &protocol::pty_resize_payload(200, 60),
    );
    assert_received_error(&stale, 43, ERROR_INVALID_REQUEST);

    let manual_input_effect = protocol::pty_input_payload([0x73; 16], b"manual input\n").unwrap();
    let manual_input_payload = protocol::encode_operation_control_payload(
        control_message::INPUT,
        protocol::OperationSource::Manual,
        None,
        &manual_input_effect,
    )
    .unwrap();
    send_operation_from(
        &mut first,
        control_message::INPUT,
        3,
        protocol::OperationSource::Manual,
        None,
        &manual_input_effect,
    );
    let manual_signal_effect = host::signal_payload(0xffff, libc::SIGCONT);
    let manual_signal_payload = protocol::encode_operation_control_payload(
        control_message::SIGNAL,
        protocol::OperationSource::Manual,
        None,
        &manual_signal_effect,
    )
    .unwrap();
    send_operation_from(
        &mut first,
        control_message::SIGNAL,
        4,
        protocol::OperationSource::Manual,
        None,
        &manual_signal_effect,
    );
    wait_for_command_result(host.directory(), 4);
    let acknowledged_event_id = journal_reader::read(host.directory(), 0)
        .unwrap()
        .events
        .last()
        .unwrap()
        .event_id;

    let mut second = connect(host.directory());
    let reconnect_effect = protocol::pty_resize_payload(107, 37);
    let reconnect_payload = protocol::encode_operation_control_payload(
        control_message::RESIZE,
        protocol::OperationSource::Manual,
        None,
        &reconnect_effect,
    )
    .unwrap();
    send_operation_from(
        &mut second,
        control_message::RESIZE,
        1,
        protocol::OperationSource::Manual,
        None,
        &reconnect_effect,
    );
    let manual_ack_effect = acknowledged_event_id.to_le_bytes();
    let manual_ack_payload = protocol::encode_operation_control_payload(
        control_message::ACK_JOURNAL,
        protocol::OperationSource::Manual,
        None,
        &manual_ack_effect,
    )
    .unwrap();
    send_operation_from(
        &mut second,
        control_message::ACK_JOURNAL,
        5,
        protocol::OperationSource::Manual,
        None,
        &manual_ack_effect,
    );
    let manual_terminate_effect = [1, 0, 0, 0];
    let manual_terminate_payload = protocol::encode_operation_control_payload(
        control_message::TERMINATE,
        protocol::OperationSource::Manual,
        None,
        &manual_terminate_effect,
    )
    .unwrap();
    send_operation_from(
        &mut second,
        control_message::TERMINATE,
        6,
        protocol::OperationSource::Manual,
        None,
        &manual_terminate_effect,
    );
    drop(first);
    drop(second);

    assert!(host.wait().success());
    let journal = journal_reader::read(host.directory(), 0).unwrap();
    let resizes: Vec<_> = journal
        .events
        .iter()
        .filter(|event| event.event_type == event_type::PTY_RESIZE)
        .map(|event| (u32_at(&event.payload[0..4]), u32_at(&event.payload[4..8])))
        .collect();
    assert!(resizes.ends_with(&[
        (101, 31),
        (102, 32),
        (103, 33),
        (104, 34),
        (105, 35),
        (106, 36),
        (107, 37)
    ]));

    assert!(journal.events.iter().any(|event| {
        event.event_type == event_type::PTY_INPUT && event.payload == manual_input_effect
    }));
    assert!(journal.events.iter().any(|event| {
        event.event_type == event_type::SIGNAL && event.payload == manual_signal_effect
    }));
    let result_identities: Vec<_> = journal
        .events
        .iter()
        .filter(|event| event.event_type == event_type::COMMAND_RESULT)
        .map(|event| (u16_at(&event.payload[0..2]), u64_at(&event.payload[2..10])))
        .collect();
    assert_eq!(
        result_identities,
        [
            (protocol::OperationSource::Server.wire_code(), 42),
            (protocol::OperationSource::Manual.wire_code(), 1),
            (
                protocol::OperationSource::Manual.wire_code(),
                u64::MAX - 1,
            ),
            (protocol::OperationSource::Server.wire_code(), 43),
            (protocol::OperationSource::Manual.wire_code(), 2),
            (protocol::OperationSource::Manual.wire_code(), 1),
            (protocol::OperationSource::Manual.wire_code(), 3),
            (protocol::OperationSource::Manual.wire_code(), 4),
            (protocol::OperationSource::Manual.wire_code(), 1),
            (protocol::OperationSource::Manual.wire_code(), 5),
            (protocol::OperationSource::Manual.wire_code(), 6),
        ]
    );

    let manual_reconnect_result = journal.events.iter().find(|event| {
        event.event_type == event_type::COMMAND_RESULT
            && u16_at(&event.payload[0..2]) == protocol::OperationSource::Manual.wire_code()
            && u64_at(&event.payload[2..10]) == 1
            && event.payload[14..14 + reconnect_payload.len()] == reconnect_payload
    });
    assert!(manual_reconnect_result.is_some());
    for (sequence, expected_envelope) in [
        (3, manual_input_payload.as_slice()),
        (4, manual_signal_payload.as_slice()),
        (5, manual_ack_payload.as_slice()),
        (6, manual_terminate_payload.as_slice()),
    ] {
        let result = journal.events.iter().find(|event| {
            event.event_type == event_type::COMMAND_RESULT
                && u16_at(&event.payload[0..2]) == protocol::OperationSource::Manual.wire_code()
                && u64_at(&event.payload[2..10]) == sequence
        });
        let result = result.unwrap();
        let envelope_length = u32_at(&result.payload[10..14]) as usize;
        assert_eq!(
            &result.payload[14..14 + envelope_length],
            expected_envelope
        );
    }
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
    send_operation(
        &mut stream,
        control_message::INPUT,
        1,
        b"server-envelope-metadata-input",
        &input,
    );
    wait_for_output(host.directory(), b"GOT:hello");

    let signal = host::signal_payload(1, -1);
    send_operation(
        &mut stream,
        control_message::SIGNAL,
        2,
        b"server-envelope-metadata-signal",
        &signal,
    );
    drop(stream);

    let process_status = host.wait_with_timeout(Duration::from_secs(2));
    assert!(
        process_status.success(),
        "session-host exited with {process_status}"
    );
    let metadata_after = fs::read(host.directory().join("metadata")).unwrap();
    assert_eq!(metadata_after, metadata_before);

    let result = journal_reader::read(host.directory(), 0).unwrap();
    assert!(
        result
            .events
            .iter()
            .any(|event| event.event_type == event_type::PTY_INPUT)
    );
    assert!(
        result
            .events
            .iter()
            .any(|event| event.event_type == event_type::PTY_OUTPUT)
    );
    assert!(
        result
            .events
            .iter()
            .any(|event| event.event_type == event_type::SIGNAL)
    );
}

#[test]
fn duplicate_operation_is_rejected_by_the_high_watermark() {
    let directory = temporary_directory("duplicate-operation-high-watermark");
    let mut host = HostGuard::spawn(
        directory,
        &["/bin/sh", "-c", "sleep 30"],
        "xterm-256color",
        80,
        24,
    );
    let mut stream = connect(host.directory());
    let terminate = [1_u8, 0, 0, 0];
    send_operation(
        &mut stream,
        control_message::TERMINATE,
        1,
        b"server-envelope-terminate-after-exit",
        &terminate,
    );

    let deadline = Instant::now() + Duration::from_secs(2);
    loop {
        let status = request(&mut stream, control_message::STATUS, 2, &[]);
        assert_eq!(status.message_type, control_message::STATUS_RESPONSE);
        if u16_at(&status.payload[2..4]) & 2 == 0 {
            break;
        }
        assert!(
            Instant::now() < deadline,
            "child remained live after TERMINATE"
        );
        thread::sleep(Duration::from_millis(5));
    }

    let retry = operation_request(
        &mut stream,
        control_message::TERMINATE,
        1,
        b"server-envelope-terminate-after-exit",
        &terminate,
    );
    assert_received_error(&retry, 1, ERROR_INVALID_REQUEST);
    drop(stream);
    assert!(host.wait().success());
}

#[test]
fn durable_acknowledgement_controls_retention() {
    let directory = temporary_directory("control-ack");
    let mut host = HostGuard::spawn_with_options(
        directory,
        &["--journal-segment-bytes", "1", "--journal-max-bytes", "1"],
        &["/bin/sh", "-c", "printf READY; sleep 30"],
        "xterm-256color",
        80,
        24,
    );
    wait_for_output(host.directory(), b"READY");

    let mut stream = connect(host.directory());
    let resize = protocol::pty_resize_payload(90, 30);
    send_operation(
        &mut stream,
        control_message::RESIZE,
        10,
        b"server-envelope-resize-10",
        &resize,
    );
    wait_for_event(host.directory(), event_type::COMMAND_RESULT);
    let first_result_id = journal_reader::read(host.directory(), 0)
        .unwrap()
        .events
        .last()
        .unwrap()
        .event_id;

    send_operation(
        &mut stream,
        control_message::RESIZE,
        11,
        b"server-envelope-resize-11",
        &resize,
    );

    send_journal_ack(&mut stream, 12, 0);
    wait_for_command_result(host.directory(), 12);
    send_journal_ack(&mut stream, 13, u64::MAX);
    wait_for_command_result(host.directory(), 13);

    wait_for_compressed_segment(host.directory());
    let segment_count_before = journal_file_count(host.directory());
    assert!(segment_count_before > 1);

    send_journal_ack(&mut stream, 14, first_result_id);
    wait_for_command_result(host.directory(), 14);
    assert_eq!(
        fs::read_to_string(host.directory().join(STATE_FILE_NAME)).unwrap(),
        format!(r#"{{"stateVersion":1,"acknowledgedEventId":{first_result_id}}}"#),
    );
    wait_for_journal_file_count_below(host.directory(), segment_count_before);

    send_journal_ack(&mut stream, 15, first_result_id - 1);
    wait_for_command_result(host.directory(), 15);

    let stale = operation_request(
        &mut stream,
        control_message::RESIZE,
        9,
        b"server-envelope-resize-9",
        &resize,
    );
    assert_received_error(&stale, 9, ERROR_INVALID_REQUEST);
    send_operation(
        &mut stream,
        control_message::RESIZE,
        16,
        b"server-envelope-resize-16",
        &resize,
    );

    let result = journal_reader::read_after(host.directory(), first_result_id).unwrap();
    assert!(
        result
            .events
            .iter()
            .all(|event| event.event_type != control_message::ACK_JOURNAL)
    );
    let metadata = fs::read_to_string(host.directory().join("metadata")).unwrap();
    assert!(!metadata.contains("acknowledg"));

    drop(stream);
    kill_recorded_child(host.directory());
    assert!(host.wait().success());
}

#[cfg(target_os = "linux")]
#[test]
fn force_waits_for_and_reaps_a_double_fork_setsid_descendant_after_pty_closure() {
    let directory = temporary_directory("double-fork-closed-pty");
    let pid_file = directory.join("grandchild.pid");
    let mut host = HostGuard::spawn(
        directory,
        &[
            "/usr/bin/perl",
            "-MPOSIX",
            "-e",
            concat!(
                "pipe(my $ready_read, my $ready_write) or die; ",
                "defined(my $first = fork) or die; if ($first) { close $ready_write; ",
                "sysread($ready_read, my $ready, 1) == 1 or die; exit 0; } ",
                "close $ready_read; defined(my $second = fork) or die; exit 0 if $second; ",
                "POSIX::setsid() >= 0 or die; ",
                "open STDIN, q(<), q(/dev/null) or die; ",
                "open STDOUT, q(>), q(/dev/null) or die; ",
                "open STDERR, q(>), q(/dev/null) or die; ",
                "open my $pid_file, q(>), $ARGV[0] or die; ",
                "print {$pid_file} qq($$\n); close $pid_file; ",
                "syswrite($ready_write, q(1), 1) == 1 or die; close $ready_write; sleep 30",
            ),
            pid_file.to_str().unwrap(),
        ],
        "xterm-256color",
        80,
        24,
    );
    let descendant = wait_for_pid_file(&pid_file);
    let leader = i32::try_from(
        journal::read_metadata(host.directory())
            .unwrap()
            .child_pid
            .unwrap(),
    )
    .unwrap();
    wait_for_process_exit(leader);
    assert!(host.child.as_mut().unwrap().try_wait().unwrap().is_none());

    let mut stream = connect(host.directory());
    let terminate = [1_u8, 0, 0, 0];
    let response = operation_request(
        &mut stream,
        control_message::TERMINATE,
        1,
        b"server-envelope-double-fork",
        &terminate,
    );
    assert_eq!(response.message_type, control_message::RECEIVED);
    drop(stream);

    assert!(host.wait_with_timeout(Duration::from_secs(3)).success());
    wait_for_process_exit(descendant);
}

#[cfg(target_os = "linux")]
#[test]
fn explicit_force_finds_a_descendant_forked_after_graceful_termination() {
    let directory = temporary_directory("fork-during-grace");
    let pid_file = directory.join("late-child.pid");
    let mut host = HostGuard::spawn(
        directory,
        &[
            "/usr/bin/perl",
            "-e",
            concat!(
                "$| = 1; my $spawned = 0; ",
                "$SIG{TERM} = sub { return if $spawned++; ",
                "defined(my $pid = fork) or die; if ($pid == 0) { ",
                "$SIG{TERM} = q(IGNORE); sleep 30; exit 0; } ",
                "open my $pid_file, q(>), $ARGV[0] or die; ",
                "print {$pid_file} qq($pid\n); close $pid_file; }; ",
                "print q(READY); sleep 30 while 1",
            ),
            pid_file.to_str().unwrap(),
        ],
        "xterm-256color",
        80,
        24,
    );
    wait_for_output(host.directory(), b"READY");

    let terminate = [0_u8; 4];
    let mut stream = connect(host.directory());
    let response = operation_request(
        &mut stream,
        control_message::TERMINATE,
        1,
        b"server-envelope-grace-race",
        &terminate,
    );
    assert_eq!(response.message_type, control_message::RECEIVED);
    let descendant = wait_for_pid_file(&pid_file);
    thread::sleep(Duration::from_millis(100));
    assert!(host.child.as_mut().unwrap().try_wait().unwrap().is_none());
    send_operation(&mut stream, control_message::TERMINATE, 2,
        b"server-envelope-force-late-child", &[1, 0, 0, 0]);
    drop(stream);

    assert!(host.wait_with_timeout(Duration::from_secs(4)).success());
    wait_for_process_exit(descendant);
}

#[cfg(target_os = "linux")]
#[test]
fn delegated_cgroup_contains_the_child_and_is_removed_after_host_exit() {
    let directory = temporary_directory("real-cgroup-lifecycle");
    let mut host = HostGuard::spawn(
        directory,
        &["/bin/sh", "-c", "printf READY; sleep 30"],
        "xterm-256color",
        80,
        24,
    );
    wait_for_output(host.directory(), b"READY");
    let events = journal_reader::read(host.directory(), 0).unwrap().events;
    if let Some(warning) = events
        .iter()
        .find(|event| event.event_type == event_type::HOST_WARNING)
    {
        let reason = std::str::from_utf8(&warning.payload[2..]).unwrap();
        eprintln!(
            "real cgroup lifecycle test skipped: writable delegated cgroup v2 unavailable: {reason}"
        );
        return;
    }

    let child_pid = journal::read_metadata(host.directory())
        .unwrap()
        .child_pid
        .unwrap();
    let membership = fs::read_to_string(format!("/proc/{child_pid}/cgroup")).unwrap();
    let cgroup_path = resolve_unified_cgroup_path(
        &membership,
        &fs::read_to_string("/proc/self/mountinfo").unwrap(),
    );
    assert!(
        cgroup_path
            .file_name()
            .unwrap()
            .to_string_lossy()
            .starts_with("orion-session-")
    );

    let mut stream = connect(host.directory());
    let terminate = [1_u8, 0, 0, 0];
    let response = operation_request(
        &mut stream,
        control_message::TERMINATE,
        1,
        b"server-envelope-real-cgroup",
        &terminate,
    );
    assert_eq!(response.message_type, control_message::RECEIVED);
    drop(stream);
    assert!(host.wait_with_timeout(Duration::from_secs(3)).success());
    assert!(!cgroup_path.exists(), "session cgroup was not removed");
}

#[test]
fn sends_interactive_signals_to_the_foreground_process_group() {
    let directory = temporary_directory("foreground-signal");
    let background_pid_file = directory.join("background.pid");
    let mut host = HostGuard::spawn(
        directory,
        &[
            "/bin/sh",
            "-c",
            "set -m; sleep 30 & printf '%s\n' $! > \"$1\"; printf READY; sleep 30; printf FINISHED",
            "foreground-signal",
            background_pid_file.to_str().unwrap(),
        ],
        "xterm-256color",
        80,
        24,
    );
    wait_for_output(host.directory(), b"READY");

    let mut stream = connect(host.directory());
    let signal = host::signal_payload(1, -1);
    send_operation(
        &mut stream,
        control_message::SIGNAL,
        1,
        b"server-envelope-signal",
        &signal,
    );
    wait_for_output(host.directory(), b"FINISHED");
    let background_pid = wait_for_pid_file(&background_pid_file);
    assert_eq!(unsafe { libc::kill(background_pid, 0) }, 0);
    send_operation(&mut stream, control_message::TERMINATE, 2,
        b"server-envelope-background-terminate", &[1, 0, 0, 0]);
    drop(stream);

    let status = host.wait_with_timeout(Duration::from_secs(2));
    assert!(status.success(), "session-host exited with {status}");
    let result = journal_reader::read(host.directory(), 0).unwrap();
    assert_command_events(&result.events, event_type::SIGNAL, 1);
}

#[test]
fn blocked_pty_input_does_not_block_admission_on_another_connection() {
    let directory = temporary_directory("blocked-input");
    let mut host = HostGuard::spawn(
        directory,
        &[
            "/bin/sh",
            "-c",
            "stty raw -echo; printf READY; kill -STOP $$",
        ],
        "xterm-256color",
        80,
        24,
    );
    wait_for_output(host.directory(), b"READY");

    let input_directory = host.directory().to_owned();
    let input_thread = thread::spawn(move || {
        let mut stream = connect(&input_directory);
        let input = protocol::pty_input_payload([0x62; 16], &vec![b'x'; 1024 * 1024]).unwrap();
        send_operation(
            &mut stream,
            control_message::INPUT,
            1,
            b"server-envelope-blocked-input",
            &input,
        );
    });
    wait_for_event(host.directory(), event_type::PTY_INPUT);

    let mut stream = connect(host.directory());
    stream
        .set_read_timeout(Some(Duration::from_secs(2)))
        .unwrap();
    let status = request(&mut stream, control_message::STATUS, 2, &[]);
    assert_eq!(status.message_type, control_message::STATUS_RESPONSE);
    let latest_event_id = u64_at(&status.payload[36..44]);
    let mut acknowledgement_stream = connect(host.directory());
    send_journal_ack(&mut acknowledgement_stream, 2, latest_event_id);

    let input = protocol::pty_input_payload([0x62; 16], &vec![b'x'; 1024 * 1024]).unwrap();
    let pending = operation_request(
        &mut stream,
        control_message::INPUT,
        1,
        b"server-envelope-blocked-input",
        &input,
    );
    assert_received_error(&pending, 1, ERROR_INVALID_REQUEST);
    drop(stream);
    kill_recorded_child(host.directory());
    input_thread.join().unwrap();
    wait_for_command_result(host.directory(), 1);
    wait_for_command_result(host.directory(), 2);
    drop(acknowledgement_stream);
    assert!(host.wait().success());
    let result = journal_reader::read_after(host.directory(), latest_event_id).unwrap();
    let journal = journal_reader::read(host.directory(), 0).unwrap();
    let input_event = journal
        .events
        .iter()
        .find(|event| event.event_type == event_type::PTY_INPUT)
        .unwrap();
    assert_eq!(
        input_event.payload,
        protocol::pty_input_payload([0x62; 16], &vec![b'x'; 1024 * 1024]).unwrap()
    );
    let command_result = result
        .events
        .iter()
        .find(|event| {
            event.event_type == event_type::COMMAND_RESULT
                && event.payload.len() >= 8
                && u64_at(&event.payload[2..10]) == 1
        })
        .unwrap();
    assert_eq!(command_result.event_type, event_type::COMMAND_RESULT);
    let envelope_length = u32_at(&command_result.payload[10..14]) as usize;
    let outcome_index = 14 + envelope_length;
    assert_eq!(command_result.payload[outcome_index], 2);
}

#[test]
fn terminate_bypasses_a_blocked_pty_input() {
    let directory = temporary_directory("terminate-blocked-input");
    let mut host = HostGuard::spawn(
        directory,
        &[
            "/bin/sh",
            "-c",
            "stty raw -echo; printf READY; kill -STOP $$",
        ],
        "xterm-256color",
        80,
        24,
    );
    wait_for_output(host.directory(), b"READY");

    let input_directory = host.directory().to_owned();
    let input_thread = thread::spawn(move || {
        let mut stream = connect(&input_directory);
        let input = protocol::pty_input_payload([0x74; 16], &vec![b'x'; 1024 * 1024]).unwrap();
        send_operation(
            &mut stream,
            control_message::INPUT,
            1,
            b"server-envelope-blocked-input",
            &input,
        );
    });
    wait_for_event(host.directory(), event_type::PTY_INPUT);

    let mut terminate_stream = connect(host.directory());
    send_operation(
        &mut terminate_stream,
        control_message::TERMINATE,
        2,
        b"server-envelope-terminate",
        &[1_u8, 0, 0, 0],
    );

    let status = host.wait_with_timeout(Duration::from_secs(2));
    assert!(status.success(), "session-host exited with {status}");
    input_thread.join().unwrap();
    drop(terminate_stream);

    let result = journal_reader::read(host.directory(), 0).unwrap();
    let signal = result
        .events
        .iter()
        .find(|event| {
            event.event_type == event_type::SIGNAL
                && event.payload.len() >= 8
                && u16_at(&event.payload[0..2]) == 3
        })
        .unwrap();
    assert_eq!(&signal.payload[4..8], &(libc::SIGKILL as i32).to_le_bytes());

    for operation_sequence in [1_u64, 2_u64] {
        assert!(result.events.iter().any(|event| {
            event.event_type == event_type::COMMAND_RESULT
                && event.payload.len() >= 8
                && u64_at(&event.payload[2..10]) == operation_sequence
        }));
    }
    let input_result = result
        .events
        .iter()
        .find(|event| {
            event.event_type == event_type::COMMAND_RESULT
                && event.payload.len() >= 8
                && u64_at(&event.payload[2..10]) == 1
        })
        .unwrap();
    let envelope_length = u32_at(&input_result.payload[10..14]) as usize;
    assert_eq!(input_result.payload[14 + envelope_length], 2);
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
    let result = journal_reader::read(host.directory(), 0).unwrap();
    let exited = result
        .events
        .iter()
        .find(|event| event.event_type == event_type::PROCESS_EXITED)
        .unwrap();
    assert_eq!(
        result
            .events
            .iter()
            .filter(|event| event.event_type == event_type::PROCESS_EXITED)
            .count(),
        1
    );
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
    send_operation(
        &mut stream,
        control_message::INPUT,
        1,
        b"server-envelope-parent-input",
        &input,
    );
    drop(stream);

    wait_for_event(directory.path(), event_type::PROCESS_EXITED);
    let result = journal_reader::read(directory.path(), 0).unwrap();
    assert!(contains(&terminal_output(&result.events), b"SURVIVED:yes"));
    wait_for_process_exit(host_pid);
    process_guard.0 = None;
}

#[test]
fn host_signal_is_forwarded_to_the_child_process_tree() {
    let mut host = HostGuard::spawn(
        temporary_directory("host-signal-forwarding"),
        &["/bin/sh", "-c", "printf READY; sleep 30"],
        "xterm-256color",
        80,
        24,
    );
    wait_for_output(host.directory(), b"READY");
    let host_pid = host.child.as_ref().unwrap().id() as libc::pid_t;
    assert_eq!(unsafe { libc::kill(host_pid, libc::SIGTERM) }, 0);

    assert!(host.wait_with_timeout(Duration::from_secs(2)).success());
    let result = journal_reader::read(host.directory(), 0).unwrap();
    assert!(result.events.iter().any(|event| {
        event.event_type == event_type::SIGNAL
            && event.payload[0..2] == 2_u16.to_le_bytes()
            && event.payload[4..8] == (libc::SIGTERM as i32).to_le_bytes()
    }));
    assert_eq!(
        result
            .events
            .iter()
            .filter(|event| event.event_type == event_type::PROCESS_EXITED)
            .count(),
        1
    );
}

#[test]
fn force_terminate_accelerates_an_active_graceful_shutdown() {
    let mut host = HostGuard::spawn(
        temporary_directory("force-after-graceful"),
        &[
            "/usr/bin/perl",
            "-e",
            "$SIG{TERM} = 'IGNORE'; $| = 1; print qq(READY); sleep 30",
        ],
        "xterm-256color",
        80,
        24,
    );
    wait_for_output(host.directory(), b"READY");

    let mut stream = connect(host.directory());
    let graceful = [0_u8; 4];
    send_operation(
        &mut stream,
        control_message::TERMINATE,
        1,
        b"server-envelope-graceful-terminate",
        &graceful,
    );
    wait_for_command_result(host.directory(), 1);
    let graceful_result_id = journal_reader::read(host.directory(), 0)
        .unwrap()
        .events
        .iter()
        .find(|event| {
            event.event_type == event_type::COMMAND_RESULT
                && event.payload.len() >= 8
                && u64_at(&event.payload[2..10]) == 1
        })
        .unwrap()
        .event_id;
    send_journal_ack(&mut stream, 2, graceful_result_id);
    wait_for_command_result(host.directory(), 2);

    let started = Instant::now();
    send_operation(
        &mut stream,
        control_message::TERMINATE,
        3,
        b"server-envelope-force-terminate",
        &[1_u8, 0, 0, 0],
    );

    let status = host.wait_with_timeout(Duration::from_secs(2));
    assert!(status.success(), "session-host exited with {status}");
    assert!(started.elapsed() < Duration::from_secs(2));

    let result = journal_reader::read(host.directory(), 0).unwrap();
    let signals: Vec<_> = result
        .events
        .iter()
        .filter(|event| event.event_type == event_type::SIGNAL)
        .collect();
    assert_eq!(signals.len(), 2);
    assert_eq!(signals[0].payload[0..2], 2_u16.to_le_bytes());
    assert_eq!(signals[1].payload[0..2], 3_u16.to_le_bytes());
    assert_eq!(
        result
            .events
            .iter()
            .filter(|event| event.event_type == event_type::PROCESS_EXITED)
            .count(),
        1
    );
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
    let terminate = [1_u8, 0, 0, 0];
    send_operation(
        &mut stream,
        control_message::TERMINATE,
        1,
        b"server-envelope-duplicate-host-terminate",
        &terminate,
    );
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
            assert!(
                Instant::now() < deadline,
                "timed out waiting for session-host"
            );
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
            let terminate = [1_u8, 0, 0, 0];
            send_operation(
                &mut stream,
                control_message::TERMINATE,
                u64::MAX - 1,
                b"server-envelope-test-cleanup",
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
    base_arguments_with_cwd(directory, session_id, Path::new("/tmp"), term, cols, rows)
}

fn base_arguments_with_cwd(
    directory: &Path,
    session_id: &str,
    cwd: &Path,
    term: &str,
    cols: u16,
    rows: u16,
) -> Vec<String> {
    vec![
        "--session-id".to_owned(),
        session_id.to_owned(),
        "--start-command-id".to_owned(),
        "command.start".to_owned(),
        "--session-dir".to_owned(),
        directory.display().to_string(),
        "--cwd".to_owned(),
        cwd.display().to_string(),
        "--cols".to_owned(),
        cols.to_string(),
        "--rows".to_owned(),
        rows.to_string(),
        "--term".to_owned(),
        term.to_owned(),
    ]
}

fn assert_start_failure(events: &[JournalEvent], diagnostic_fragment: &str) {
    assert_start_prefix(events, event_type::SESSION_START_FAILED);
    let event = events.iter().find(|event| event.event_type == event_type::SESSION_START_FAILED).unwrap();
    let command_id_length = usize::from(u16_at(&event.payload[0..2]));
    let command_id_end = 2 + command_id_length;
    assert_eq!(&event.payload[2..command_id_end], b"command.start");
    assert_eq!(
        u64_at(&event.payload[command_id_end..command_id_end + 8]),
        0
    );
    let diagnostic = std::str::from_utf8(&event.payload[command_id_end + 8..]).unwrap();
    assert!(
        diagnostic.contains(diagnostic_fragment),
        "unexpected start failure diagnostic: {diagnostic}"
    );
}

fn assert_start_prefix(events: &[JournalEvent], outcome: u16) {
    let outcomes = events
        .iter()
        .filter(|event| {
            matches!(
                event.event_type,
                event_type::PROCESS_STARTED | event_type::SESSION_START_FAILED
            )
        })
        .collect::<Vec<_>>();
    assert_eq!(outcomes.len(), 1);
    assert_eq!(outcomes[0].event_type, outcome);
    let outcome_index = events
        .iter()
        .position(|event| std::ptr::eq(event, outcomes[0]))
        .unwrap();
    assert!(
        events[..outcome_index]
            .iter()
            .all(|event| event.event_type == event_type::HOST_WARNING)
    );
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
    sequence: u64,
    payload: &[u8],
) -> OwnedControlFrame {
    request_with_schema(stream, message_type, 1, sequence, payload)
}

fn operation_request(
    stream: &mut UnixStream,
    message_type: u16,
    operation_sequence: u64,
    command_envelope: &[u8],
    effect: &[u8],
) -> OwnedControlFrame {
    operation_request_from(
        stream,
        message_type,
        operation_sequence,
        protocol::OperationSource::Server,
        Some(command_envelope),
        effect,
    )
}

fn operation_request_from(
    stream: &mut UnixStream,
    message_type: u16,
    operation_sequence: u64,
    source: protocol::OperationSource,
    server_envelope: Option<&[u8]>,
    effect: &[u8],
) -> OwnedControlFrame {
    let payload =
        protocol::encode_operation_control_payload(message_type, source, server_envelope, effect)
            .unwrap();
    request_with_schema(stream, message_type, 3, operation_sequence, &payload)
}

fn send_operation(
    stream: &mut UnixStream,
    message_type: u16,
    operation_sequence: u64,
    command_envelope: &[u8],
    effect: &[u8],
) {
    send_operation_from(
        stream,
        message_type,
        operation_sequence,
        protocol::OperationSource::Server,
        Some(command_envelope),
        effect,
    )
}

fn send_operation_from(
    stream: &mut UnixStream,
    message_type: u16,
    operation_sequence: u64,
    source: protocol::OperationSource,
    server_envelope: Option<&[u8]>,
    effect: &[u8],
) {
    let payload =
        protocol::encode_operation_control_payload(message_type, source, server_envelope, effect)
            .unwrap();
    let bytes = protocol::encode_control_frame(ControlFrame {
        message_type,
        payload_schema_version: 3,
        flags: 0,
        sequence: operation_sequence,
        payload: &payload,
    })
    .unwrap();
    stream.write_all(&bytes).unwrap_or_else(|error| {
        panic!("cannot write control message {message_type:#06x} sequence {operation_sequence}: {error}")
    });
    let response = host::read_control_frame(stream).unwrap().unwrap();
    assert_received(&response, operation_sequence);
}

fn send_journal_ack(stream: &mut UnixStream, operation_sequence: u64, event_id: u64) {
    send_operation(
        stream,
        control_message::ACK_JOURNAL,
        operation_sequence,
        &[0x81, 0x07],
        &event_id.to_le_bytes(),
    );
}

fn request_with_schema(
    stream: &mut UnixStream,
    message_type: u16,
    payload_schema_version: u16,
    sequence: u64,
    payload: &[u8],
) -> OwnedControlFrame {
    let bytes = protocol::encode_control_frame(ControlFrame {
        message_type,
        payload_schema_version,
        flags: 0,
        sequence,
        payload,
    })
    .unwrap();
    stream.write_all(&bytes).unwrap_or_else(|error| {
        panic!("cannot write control message {message_type:#06x} sequence {sequence}: {error}")
    });
    host::read_control_frame(stream)
        .unwrap_or_else(|error| {
            panic!("cannot read control message {message_type:#06x} sequence {sequence}: {error}")
        })
        .unwrap_or_else(|| {
            panic!("control connection closed for message {message_type:#06x} sequence {sequence}")
        })
}

fn assert_received(frame: &OwnedControlFrame, sequence: u64) {
    assert_eq!(frame.message_type, control_message::RECEIVED);
    assert_eq!(frame.sequence, sequence);
    assert!(frame.payload.is_empty());
}

fn assert_received_error(frame: &OwnedControlFrame, sequence: u64, code: u32) {
    assert_eq!(frame.message_type, control_message::RECEIVED);
    assert_eq!(frame.sequence, sequence);
    assert!(frame.payload.len() >= 4);
    assert_eq!(u32_at(&frame.payload[..4]), code);
}

fn assert_command_events(events: &[JournalEvent], effect_type: u16, operation_sequence: u64) {
    let result_index = events
        .iter()
        .position(|event| {
            event.event_type == event_type::COMMAND_RESULT
                && u64_at(&event.payload[2..10]) == operation_sequence
        })
        .unwrap();
    assert!(
        events[..result_index]
            .iter()
            .any(|event| event.event_type == effect_type)
    );
}

fn wait_for_output(directory: &Path, expected: &[u8]) {
    let deadline = Instant::now() + TIMEOUT;
    loop {
        if let Ok(result) = journal_reader::read(directory, 0)
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
    wait_for_event_count(directory, expected, 1);
}

fn wait_for_command_result(directory: &Path, operation_sequence: u64) {
    let deadline = Instant::now() + TIMEOUT;
    loop {
        if let Ok(result) = journal_reader::read(directory, 0)
            && result.events.iter().any(|event| {
                event.event_type == event_type::COMMAND_RESULT
                    && event.payload.len() >= 8
                    && u64_at(&event.payload[2..10]) == operation_sequence
            })
        {
            return;
        }
        assert!(
            Instant::now() < deadline,
            "timed out waiting for COMMAND_RESULT operation {operation_sequence}"
        );
        thread::sleep(Duration::from_millis(10));
    }
}

fn wait_for_event_count(directory: &Path, expected: u16, count: usize) {
    let deadline = Instant::now() + TIMEOUT;
    loop {
        if let Ok(result) = journal_reader::read(directory, 0)
            && result
                .events
                .iter()
                .filter(|event| event.event_type == expected)
                .count()
                >= count
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

fn wait_for_compressed_segment(directory: &Path) {
    let deadline = Instant::now() + TIMEOUT;
    loop {
        if !journal_segment_numbers(directory).0.is_empty() {
            return;
        }
        assert!(
            Instant::now() < deadline,
            "timed out waiting for compressed journal segment"
        );
        thread::sleep(Duration::from_millis(10));
    }
}

fn wait_for_journal_file_count_below(directory: &Path, limit: usize) {
    let deadline = Instant::now() + TIMEOUT;
    loop {
        if journal_file_count(directory) < limit {
            return;
        }
        assert!(
            Instant::now() < deadline,
            "timed out waiting for journal retention"
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

fn wait_for_pid_file(path: &Path) -> i32 {
    let deadline = Instant::now() + TIMEOUT;
    loop {
        if let Ok(contents) = fs::read_to_string(path)
            && let Ok(pid) = contents.trim().parse::<i32>()
        {
            return pid;
        }
        if Instant::now() >= deadline {
            let entries = fs::read_dir(path.parent().unwrap())
                .into_iter()
                .flatten()
                .filter_map(Result::ok)
                .map(|entry| entry.file_name())
                .collect::<Vec<_>>();
            panic!(
                "timed out waiting for PID file {}; directory entries: {entries:?}",
                path.display()
            );
        }
        thread::sleep(Duration::from_millis(10));
    }
}

#[cfg(target_os = "linux")]
fn resolve_unified_cgroup_path(membership: &str, mountinfo: &str) -> PathBuf {
    let membership = membership
        .lines()
        .find_map(|line| line.strip_prefix("0::"))
        .map(PathBuf::from)
        .expect("unified cgroup membership is missing");
    let mut best = None;
    for line in mountinfo.lines() {
        let fields = line.split_ascii_whitespace().collect::<Vec<_>>();
        let Some(separator) = fields.iter().position(|field| *field == "-") else {
            continue;
        };
        if fields.get(separator + 1) != Some(&"cgroup2") {
            continue;
        }
        let root = PathBuf::from(decode_mountinfo_test_path(fields[3]));
        let Ok(relative) = membership.strip_prefix(&root) else {
            continue;
        };
        let candidate = PathBuf::from(decode_mountinfo_test_path(fields[4])).join(relative);
        if best.as_ref().is_none_or(|(depth, _): &(usize, PathBuf)| {
            root.components().count() > *depth
        }) {
            best = Some((root.components().count(), candidate));
        }
    }
    best.map(|(_, path)| path)
        .expect("unified cgroup mount is missing")
}

#[cfg(target_os = "linux")]
fn decode_mountinfo_test_path(path: &str) -> String {
    path.replace("\\040", " ")
        .replace("\\011", "\t")
        .replace("\\012", "\n")
        .replace("\\134", "\\")
}

fn terminal_output(events: &[JournalEvent]) -> Vec<u8> {
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

fn journal_file_count(directory: &Path) -> usize {
    let (compressed, active) = journal_segment_numbers(directory);
    compressed.len() + active.len()
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
