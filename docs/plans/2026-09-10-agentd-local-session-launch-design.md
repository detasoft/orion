# AgentD Local Session Launch Design

Status: approved on 2026-09-10.

## Goal

Provide the earliest useful local path from AgentD to a real `session-host`:

```text
agentd terminal start --session-host PATH --state-dir PATH [options] -- COMMAND...
```

The command starts the host, waits until its manifest, journal, and control
endpoint establish durable handoff, prints the session identity and directory,
and exits. It does not attach the caller's terminal.

## Design

`AgentdMain` recognizes `terminal start` before daemon argument parsing. All
other invocations retain the existing daemon behavior, so provisioning and
server launch remain unchanged. Local mode never reads stdin as a launch permit,
constructs `Agent`, acquires the daemon process lock, or initializes Jetty.

The local command parses `--session-host`, `--state-dir`, optional
`--session-id`, and optional `--cwd`, followed by the child command after `--`.
It derives the sessions directory from the state directory, generates omitted
session and start-command identities, uses a fixed 80 by 24 PTY size, reads
`TERM` and `COLORTERM` when valid, and otherwise uses `xterm-256color` without a
color-terminal value. Sandbox policy and arbitrary child environment are out of
scope for this first slice.

The command builds the existing `SessionSpec` and calls the existing
`NativeRuntime`. A `Started` result is printed as one stable line containing the
session ID and normalized directory. Invalid CLI input returns exit code 2;
launch and handoff failures return exit code 1 with the existing failure kind
and a detail truncated to 512 characters. The launched host remains independent
when the Java process exits.

## Verification

Unit tests cover routing without stdin access, parsing/defaults, successful
result rendering, and validation or launch failures. A live-peer test uses the
packaged native executable to prove durable handoff and continued host liveness
after the AgentD command returns.

## Deferred Work

Interactive attachment, raw-terminal ownership, journal replay/following,
input, resize, manual sequencing, signals, termination, and acknowledgement
remain in the broader local-terminal design.
