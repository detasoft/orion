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

use orion_session_host::host::{self, ERROR_INVALID_REQUEST, ERROR_POLICY, OwnedControlFrame};
use orion_session_host::journal::{self, Metadata};
use orion_session_host::journal_acknowledgement::STATE_FILE_NAME;
use orion_session_host::protocol::{
    self, ControlFrame, control_message, event_type,
};
use support::journal::{self as journal_reader, JournalEvent};

const TIMEOUT: Duration = Duration::from_secs(10);
static NEXT_DIRECTORY: AtomicU64 = AtomicU64::new(0);

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
    let terminate = [1_u8, 0, 0, 0, 0, 0, 0, 0];
    let terminated = request(&mut stream, control_message::TERMINATE, 2, &terminate);
    assert_eq!(terminated.message_type, control_message::ACCEPTED);
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
        .args(["--sandbox-policy", policy.to_str().unwrap(), "--", "/bin/sh", "-c", "exit 0"])
        .output()
        .unwrap();
    assert!(output.status.success(), "{}", String::from_utf8_lossy(&output.stderr));
    assert!(
        String::from_utf8_lossy(&output.stderr)
            .contains("warning: Landlock ABI 9 is unavailable; running without filesystem restrictions")
    );
    let metadata = journal::read_metadata(directory).unwrap();
    assert!(metadata.sandbox.requested);
    assert_eq!(metadata.sandbox.enforcement, journal::SandboxEnforcement::None);
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
    fs::write(&policy, encode_policy(&[(directory.path().to_path_buf(), 12)])).unwrap();
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
    assert!(output.status.success(), "{}", String::from_utf8_lossy(&output.stderr));
    assert!(
        String::from_utf8_lossy(&output.stderr)
            .contains("warning: Landlock ABI 9 is unavailable; running without filesystem restrictions")
    );
    let metadata = journal::read_metadata(directory.path()).unwrap();
    assert!(metadata.sandbox.requested);
    assert_eq!(metadata.sandbox.enforcement, journal::SandboxEnforcement::None);
    assert_eq!(
        metadata.sandbox.unavailable_policy,
        journal::SandboxUnavailablePolicy::RunUnsandboxed
    );
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
    let result = journal_reader::read(host.directory(), 0).unwrap();
    let output = terminal_output(&result.events);
    assert!(contains(&output, b"\x1b[31mraw\xff\x1b[0m"));
    assert_eq!(
        result.events.first().unwrap().event_type,
        event_type::PROCESS_STARTED
    );
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
fn orders_idempotent_controls_and_reuses_results_after_reconnect() {
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
    let journal_at_status = journal_reader::read(host.directory(), 0).unwrap();
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
    let resized = operation_request(
        &mut first,
        control_message::RESIZE,
        2,
        1,
        b"resize-1",
        b"server-envelope-resize",
        &resize,
    );
    assert_eq!(resized.message_type, control_message::ACCEPTED);
    let resize_result_event_id = u64_at(&resized.payload);

    let input_id = [0x4a; 16];
    let input = protocol::pty_input_payload(input_id, b"hello\n").unwrap();
    let accepted = operation_request(
        &mut first,
        control_message::INPUT,
        3,
        2,
        b"input-2",
        b"server-envelope-input",
        &input,
    );
    assert_eq!(accepted.message_type, control_message::ACCEPTED);
    let input_result_event_id = u64_at(&accepted.payload);
    assert!(resize_result_event_id < input_result_event_id);
    drop(first);

    let mut reconnected = connect(host.directory());
    let duplicate = operation_request(
        &mut reconnected,
        control_message::INPUT,
        4,
        2,
        b"input-2",
        b"server-envelope-input",
        &input,
    );
    assert_eq!(duplicate.message_type, control_message::ACCEPTED);
    assert_eq!(u64_at(&duplicate.payload), input_result_event_id);

    let changed_retry = protocol::pty_input_payload(input_id, b"second\n").unwrap();
    let conflict = operation_request(
        &mut reconnected,
        control_message::INPUT,
        5,
        2,
        b"input-2",
        b"server-envelope-input",
        &changed_retry,
    );
    assert_error(&conflict, ERROR_INVALID_REQUEST);
    let command_id_conflict = operation_request(
        &mut reconnected,
        control_message::INPUT,
        6,
        2,
        b"input-2-changed",
        b"server-envelope-input",
        &input,
    );
    assert_error(&command_id_conflict, ERROR_INVALID_REQUEST);
    let envelope_conflict = operation_request(
        &mut reconnected,
        control_message::INPUT,
        7,
        2,
        b"input-2",
        b"server-envelope-input-changed",
        &input,
    );
    assert_error(&envelope_conflict, ERROR_INVALID_REQUEST);

    wait_for_output(host.directory(), b"GOT:hello");
    let terminate = [1_u8, 0, 0, 0, 0, 0, 0, 0];
    let terminated = operation_request(
        &mut reconnected,
        control_message::TERMINATE,
        8,
        5,
        b"terminate-5",
        b"server-envelope-terminate",
        &terminate,
    );
    assert_eq!(terminated.message_type, control_message::ACCEPTED);
    let terminate_result_event_id = u64_at(&terminated.payload);
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
    assert_command_triplet(&result.events, event_type::PTY_RESIZE, resize_result_event_id);
    assert_command_triplet(&result.events, event_type::PTY_INPUT, input_result_event_id);
    assert_command_triplet(
        &result.events,
        event_type::SIGNAL,
        terminate_result_event_id,
    );
    assert_eq!(
        result
            .events
            .iter()
            .filter(|event| event.event_type == event_type::COMMAND_ACCEPTED)
            .count(),
        3,
    );
    assert_eq!(
        result
            .events
            .iter()
            .filter(|event| event.event_type == event_type::COMMAND_RESULT)
            .count(),
        3,
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
    let accepted = operation_request(
        &mut stream,
        control_message::INPUT,
        2,
        1,
        b"metadata-input",
        b"server-envelope-metadata-input",
        &input,
    );
    assert_eq!(accepted.message_type, control_message::ACCEPTED);
    wait_for_output(host.directory(), b"GOT:hello");

    let signal = host::signal_payload(1, -1);
    let signalled = operation_request(
        &mut stream,
        control_message::SIGNAL,
        3,
        2,
        b"metadata-signal",
        b"server-envelope-metadata-signal",
        &signal,
    );
    assert_eq!(signalled.message_type, control_message::ACCEPTED);
    drop(stream);

    let process_status = host.wait_with_timeout(Duration::from_secs(2));
    assert!(process_status.success(), "session-host exited with {process_status}");
    let metadata_after = fs::read(host.directory().join("metadata")).unwrap();
    assert_eq!(metadata_after, metadata_before);

    let result = journal_reader::read(host.directory(), 0).unwrap();
    assert!(result.events.iter().any(|event| event.event_type == event_type::PTY_INPUT));
    assert!(result.events.iter().any(|event| event.event_type == event_type::PTY_OUTPUT));
    assert!(result.events.iter().any(|event| event.event_type == event_type::SIGNAL));
}

#[test]
fn completed_retry_returns_its_result_after_the_child_exits() {
    let directory = temporary_directory("completed-retry-after-exit");
    let mut host = HostGuard::spawn(
        directory,
        &["/bin/sh", "-c", "sleep 30"],
        "xterm-256color",
        80,
        24,
    );
    let mut stream = connect(host.directory());
    let terminate = [1_u8, 0, 0, 0, 0, 0, 0, 0];
    let first = operation_request(
        &mut stream,
        control_message::TERMINATE,
        1,
        1,
        b"terminate-after-exit",
        b"server-envelope-terminate-after-exit",
        &terminate,
    );
    assert_eq!(first.message_type, control_message::ACCEPTED);
    let result_event_id = u64_at(&first.payload);

    let deadline = Instant::now() + Duration::from_secs(2);
    loop {
        let status = request(&mut stream, control_message::STATUS, 2, &[]);
        assert_eq!(status.message_type, control_message::STATUS_RESPONSE);
        if u16_at(&status.payload[2..4]) & 2 == 0 {
            break;
        }
        assert!(Instant::now() < deadline, "child remained live after TERMINATE");
        thread::sleep(Duration::from_millis(5));
    }

    let retry = operation_request(
        &mut stream,
        control_message::TERMINATE,
        3,
        1,
        b"terminate-after-exit",
        b"server-envelope-terminate-after-exit",
        &terminate,
    );
    assert_eq!(retry.message_type, control_message::ACCEPTED);
    assert_eq!(u64_at(&retry.payload), result_event_id);
    drop(stream);
    assert!(host.wait().success());
}

#[test]
fn durable_acknowledgement_controls_retention_and_ledger_capacity() {
    let directory = temporary_directory("control-ack");
    let mut host = HostGuard::spawn_with_options(
        directory,
        &[
            "--journal-segment-bytes",
            "1",
            "--journal-max-bytes",
            "1",
            "--max-unacknowledged-operations",
            "1",
        ],
        &["/bin/sh", "-c", "printf READY; sleep 30"],
        "xterm-256color",
        80,
        24,
    );
    wait_for_output(host.directory(), b"READY");

    let mut stream = connect(host.directory());
    let resize = protocol::pty_resize_payload(90, 30);
    let accepted = operation_request(
        &mut stream,
        control_message::RESIZE,
        1,
        10,
        b"resize-10",
        b"server-envelope-resize-10",
        &resize,
    );
    assert_eq!(accepted.message_type, control_message::ACCEPTED);
    let first_result_id = u64_at(&accepted.payload);

    let full = operation_request(
        &mut stream,
        control_message::RESIZE,
        2,
        11,
        b"resize-11",
        b"server-envelope-resize-11",
        &resize,
    );
    assert_error(&full, ERROR_POLICY);

    let schema_one = request(&mut stream, control_message::RESIZE, 3, &resize);
    assert_eq!(schema_one.message_type, control_message::ERROR);

    let zero = request(
        &mut stream,
        control_message::ACK_JOURNAL,
        4,
        &0_u64.to_le_bytes(),
    );
    assert_error(&zero, ERROR_INVALID_REQUEST);
    let future = request(
        &mut stream,
        control_message::ACK_JOURNAL,
        5,
        &(first_result_id + 1).to_le_bytes(),
    );
    assert_error(&future, ERROR_INVALID_REQUEST);

    wait_for_compressed_segment(host.directory());
    let segment_count_before = journal_file_count(host.directory());
    assert!(segment_count_before > 1);

    let acknowledged = request(
        &mut stream,
        control_message::ACK_JOURNAL,
        6,
        &first_result_id.to_le_bytes(),
    );
    assert_eq!(acknowledged.message_type, control_message::ACCEPTED);
    assert_eq!(u64_at(&acknowledged.payload), first_result_id);
    assert_eq!(
        fs::read_to_string(host.directory().join(STATE_FILE_NAME)).unwrap(),
        format!(r#"{{"stateVersion":1,"acknowledgedEventId":{first_result_id}}}"#),
    );
    assert!(journal_file_count(host.directory()) < segment_count_before);

    let lower = request(
        &mut stream,
        control_message::ACK_JOURNAL,
        7,
        &(first_result_id - 1).to_le_bytes(),
    );
    assert_eq!(lower.message_type, control_message::ACCEPTED);
    assert_eq!(u64_at(&lower.payload), first_result_id);

    let stale = operation_request(
        &mut stream,
        control_message::RESIZE,
        8,
        9,
        b"resize-9",
        b"server-envelope-resize-9",
        &resize,
    );
    assert_error(&stale, ERROR_INVALID_REQUEST);
    let admitted = operation_request(
        &mut stream,
        control_message::RESIZE,
        9,
        11,
        b"resize-11",
        b"server-envelope-resize-11",
        &resize,
    );
    assert_eq!(admitted.message_type, control_message::ACCEPTED);

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
    let terminated = operation_request(
        &mut stream,
        control_message::TERMINATE,
        2,
        1,
        b"terminate-orphan",
        b"server-envelope-terminate-orphan",
        &terminate,
    );
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
    let response = operation_request(
        &mut stream,
        control_message::SIGNAL,
        1,
        1,
        b"signal-1",
        b"server-envelope-signal",
        &signal,
    );
    assert_eq!(response.message_type, control_message::ACCEPTED);
    let result_event_id = u64_at(&response.payload);
    drop(stream);

    let status = host.wait_with_timeout(Duration::from_secs(2));
    assert!(status.success(), "session-host exited with {status}");
    let result = journal_reader::read(host.directory(), 0).unwrap();
    assert_command_triplet(&result.events, event_type::SIGNAL, result_event_id);
}

#[test]
fn blocked_pty_input_does_not_block_status_ack_or_matching_retry() {
    let directory = temporary_directory("blocked-input");
    let mut host = HostGuard::spawn(
        directory,
        &["/bin/sh", "-c", "stty raw -echo; printf READY; kill -STOP $$"],
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
            &vec![b'x'; 1024 * 1024],
        )
        .unwrap();
        operation_request(
            &mut stream,
            control_message::INPUT,
            1,
            1,
            b"blocked-input",
            b"server-envelope-blocked-input",
            &input,
        )
    });
    wait_for_event(host.directory(), event_type::PTY_INPUT);

    let mut stream = connect(host.directory());
    stream.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
    let status = request(&mut stream, control_message::STATUS, 2, &[]);
    assert_eq!(status.message_type, control_message::STATUS_RESPONSE);
    let latest_event_id = u64_at(&status.payload[36..44]);
    let acknowledged = request(
        &mut stream,
        control_message::ACK_JOURNAL,
        3,
        &latest_event_id.to_le_bytes(),
    );
    assert_eq!(acknowledged.message_type, control_message::ACCEPTED);

    let input = protocol::pty_input_payload(
        [0x62; 16],
        &vec![b'x'; 1024 * 1024],
    )
    .unwrap();
    let pending = operation_request(
        &mut stream,
        control_message::INPUT,
        4,
        1,
        b"blocked-input",
        b"server-envelope-blocked-input",
        &input,
    );
    assert_eq!(pending.message_type, control_message::ERROR);
    drop(stream);
    kill_recorded_child(host.directory());
    let completed = input_thread.join().unwrap();
    assert_eq!(completed.message_type, control_message::ACCEPTED);
    let result_event_id = u64_at(&completed.payload);
    assert!(host.wait().success());
    let result = journal_reader::read_after(host.directory(), latest_event_id).unwrap();
    let command_result = result
        .events
        .iter()
        .find(|event| event.event_id == result_event_id)
        .unwrap();
    assert_eq!(command_result.event_type, event_type::COMMAND_RESULT);
    let outcome_index = 10 + usize::from(u16_at(&command_result.payload[8..10]));
    assert_eq!(command_result.payload[outcome_index], 2);
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
    let response = operation_request(
        &mut stream,
        control_message::INPUT,
        1,
        1,
        b"parent-input",
        b"server-envelope-parent-input",
        &input,
    );
    assert_eq!(response.message_type, control_message::ACCEPTED);
    drop(stream);

    wait_for_event(directory.path(), event_type::PROCESS_EXITED);
    let result = journal_reader::read(directory.path(), 0).unwrap();
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
    let terminated = operation_request(
        &mut stream,
        control_message::TERMINATE,
        2,
        1,
        b"duplicate-host-terminate",
        b"server-envelope-duplicate-host-terminate",
        &terminate,
    );
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
            let _ = operation_request(
                &mut stream,
                control_message::TERMINATE,
                u64::MAX,
                u64::MAX,
                b"test-cleanup",
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
    assert_eq!(events.len(), 1);
    let event = &events[0];
    assert_eq!(event.event_type, event_type::SESSION_START_FAILED);
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
    request_with_schema(stream, message_type, 1, request_id, payload)
}

fn operation_request(
    stream: &mut UnixStream,
    message_type: u16,
    request_id: u64,
    operation_sequence: u64,
    command_id: &[u8],
    command_envelope: &[u8],
    effect: &[u8],
) -> OwnedControlFrame {
    let payload = protocol::encode_operation_control_payload(
        message_type,
        operation_sequence,
        command_id,
        command_envelope,
        effect,
    )
    .unwrap();
    request_with_schema(stream, message_type, 2, request_id, &payload)
}

fn request_with_schema(
    stream: &mut UnixStream,
    message_type: u16,
    payload_schema_version: u16,
    request_id: u64,
    payload: &[u8],
) -> OwnedControlFrame {
    let bytes = protocol::encode_control_frame(ControlFrame {
        message_type,
        payload_schema_version,
        flags: 0,
        request_id,
        payload,
    })
    .unwrap();
    stream.write_all(&bytes).unwrap_or_else(|error| {
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

fn assert_error(frame: &OwnedControlFrame, code: u32) {
    assert_eq!(frame.message_type, control_message::ERROR);
    assert!(frame.payload.len() >= 4);
    assert_eq!(u32_at(&frame.payload[..4]), code);
}

fn assert_command_triplet(
    events: &[JournalEvent],
    effect_type: u16,
    result_event_id: u64,
) {
    let result_index = events
        .iter()
        .position(|event| event.event_id == result_event_id)
        .unwrap();
    assert!(result_index >= 2);
    assert_eq!(events[result_index - 2].event_type, event_type::COMMAND_ACCEPTED);
    assert_eq!(events[result_index - 1].event_type, effect_type);
    assert_eq!(events[result_index].event_type, event_type::COMMAND_RESULT);
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
    let deadline = Instant::now() + TIMEOUT;
    loop {
        if let Ok(result) = journal_reader::read(directory, 0)
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
