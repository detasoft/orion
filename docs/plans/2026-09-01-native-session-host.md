# Native Session Host

Journal format amendment:
[`2026-09-02-session-journal-cbor-sequence.md`](2026-09-02-session-journal-cbor-sequence.md)
supersedes the journal framing, cursor, segmentation, compression, and
retention requirements in this plan.

## Context

Orion needs a local execution primitive for interactive agent CLIs such as
Claude, Codex, and shells. The process that owns an interactive command must not
share `agentd`'s lifecycle: restarting or temporarily stopping `agentd` must not
terminate the command or lose terminal history.

Implement `session-host` as a small standalone Rust executable. It owns the
terminal, child process, journal, sandbox policy, control endpoint, and session
lifecycle. `agentd` discovers hosts and session directories, reads journals,
and sends commands, but never owns a hosted child process.

The Maven bootstrap executable JAR carries native session-host resources under
`META-INF/orion/native/session-host/<target>/`. The release matrix contributes
Linux, macOS, and Windows builds for x86_64 and aarch64 to that resource tree.

## System Boundary

```text
agentd -- control command --> session-host -- PTY/ConPTY --> child tree
             |                    |
             +-- reads -----------+-- append-only session journal
```

The host is not a terminal emulator, network proxy, central-server client, or
semantic interpreter for agent output. ANSI and VT data remains opaque. Higher
level conversation, tool, artifact, and screen models are built above the
journal.

## Invariants

- The host is the only journal writer and assigns the total event order.
- PTY output is persisted byte-for-byte without encoding or newline changes.
- Every record gets a strictly increasing, session-relative monotonic
  nanosecond timestamp. Session metadata also records the wall-clock start.
- Input, resize, signal, process, and future harness events share one ordered
  stream.
- A control connection is optional and is never the durable event channel.
- A lost notification or `agentd` restart is recovered by scanning the journal
  after the last cursor.
- Retention may discard old closed segments but must not block terminal output;
  readers can distinguish a retention gap from an empty result.
- Filesystem restrictions apply to the child and all descendants, not to the
  host that owns the journal and control endpoint.
- Unix PTY and Windows ConPTY use the same logical journal and control
  protocols behind platform transport abstractions.

## Versioned Contracts

The first task must freeze and document binary test vectors for the journal and
control protocol before dependent implementations rely on them.

The journal contract includes:

- fixed segment, block, and record framing with magic values, format versions,
  flags, lengths, timestamps, counts, codec identifiers, and checksums;
- event-type namespaces for journal/system, terminal, process, and harness
  events;
- MVP payload schemas for `PTY_OUTPUT`, `PTY_INPUT`, `PTY_RESIZE`,
  `PROCESS_STARTED`, `PROCESS_EXITED`, and `SIGNAL`;
- skippable unknown event types and versioned schemas for structured payloads;
- raw binary payloads for terminal output and an input ID in `PTY_INPUT`;
- detection of partial records and blocks, with an unfinished tail ignored;
- cursor and gap semantics based on record timestamps.

The control contract includes `INPUT`, `RESIZE`, `SIGNAL`, `TERMINATE`,
`STATUS`, and generic typed-event submission, with `ACCEPTED`, `DUPLICATE`,
`ERROR`, and `STATUS` responses. Input IDs provide at-most-once delivery after
acceptance for the lifetime of a host, including client reconnects. The
protocol must not expose Unix-domain-socket or named-pipe details.

## Session Storage

Each session uses one directory:

```text
sessions/<session-id>/
    metadata
    control endpoint or endpoint descriptor
    journal-000001.seg
    journal-000002.seg
    ...
```

Metadata records identity, format version, creation and start times, command,
working directory, host and child process state, terminal size, sandbox
description, and oldest/latest available timestamps. Updates must be atomic and
recoverable, but journal records remain the source of truth for event history.

The journal is a segmented append-only log. Records are grouped into blocks so
Zstandard compression can be enabled without changing logical records. The
active tail may remain uncompressed until finalization. Segment size, maximum
journal size, block size, and durability policy are configurable. Initial
defaults should select a 32-128 MiB segment and a 256 KiB-1 MiB uncompressed
block based on measurement. The default retention policy is `DROP_OLDEST` over
closed segments.

## Process and Platform Behavior

The command-line contract accepts the session ID and directory, working
directory, initial columns and rows, terminal type, sandbox configuration, and
the child command after `--`.

On Linux and macOS, stdin, stdout, and stderr of the child attach to one PTY
slave and the host operates the PTY master. The child observes all three
streams as TTYs, receives the configured initial window size, and gets at least
`TERM=xterm-256color`. Output remains opaque.

On Windows, the corresponding port uses ConPTY and a named pipe. Unix control
uses a Unix domain socket. Platform-specific PTY and control implementations
sit behind internal interfaces so lifecycle, ordering, journal, and command
behavior remain common.

Resize is ordered by appending `PTY_RESIZE` and then applying the new terminal
size. Input is ordered by accepting and deduplicating its ID, appending
`PTY_INPUT`, and then writing its bytes to the terminal. This is an explicit
at-most-once policy, not a distributed exactly-once transaction.

The host records process start and exit, including exit code and termination
signal when available, finalizes the journal, and exits immediately or after a
configurable grace period. Its normal operation must not depend on the process
that launched it remaining alive.

## Linux Sandbox

When requested, the Linux child setup applies `no_new_privs` and Landlock after
fork/spawn preparation and before `exec`. A policy contains read-write and
read-only paths and is inherited by all descendants. The host retains access to
its own session files and IPC.

If the kernel cannot enforce a requested policy, the default is fail-closed.
An explicit configuration may allow unsandboxed execution. CPU, memory,
process, network, seccomp, cgroup, and namespace isolation remain future policy
providers and must not alter terminal or journal protocols.

## Delivery Order

| Order | Task | Depends on | Deliverable |
| --- | --- | --- | --- |
| 1 | Contracts and Rust build | none | Frozen v1 formats, test vectors, Rust skeleton, pinned toolchain |
| 2 | Journal core | 1 | Ordered append/read, metadata, recovery, compatibility |
| 3 | Unix process host | 1, 2 | PTY lifecycle and Unix control commands |
| 4 | Retention and compression | 2 | Segments, Zstd blocks, gap reporting, durability modes |
| 5 | Linux sandbox | 3 | Landlock policy inherited by the child tree |
| 6 | Harness event ingress | 1-3 | Structured producers share host-assigned ordering |
| 7 | Windows host | 1-4 | ConPTY and named-pipe parity |
| 8 | Release and acceptance | 3-7 | Target artifacts and host acceptance scenarios |

Journal retention can proceed in parallel with the Unix host after the journal
core is stable. Harness event ingress can proceed in parallel with the Linux
sandbox. Windows implementation follows validated common contracts and does
not block the first Linux/macOS harness slice. Agent-side discovery, journal
transport, and restart recovery are tracked separately in
[`current-work/agentd/TASK.md`](current-work/agentd/TASK.md).

## MVP Boundary

The first usable host slice comprises tasks 1-6 on Linux, with macOS PTY
support where the Unix abstraction permits it. It must run an interactive
shell or agent, accept input and resize commands, preserve ordered raw terminal
history, survive control-client loss, expose retention gaps, and enforce an
inherited Landlock policy when requested. AgentD restart and network-resume
acceptance belongs to the separate AgentD plan.

Windows parity and the complete six-target release matrix follow without
changing the v1 logical protocols. Required artifacts are Linux, macOS, and
Windows for both x86_64 and aarch64.

## Acceptance Scenarios

- Run `bash`, send `echo hello`, and reconstruct the exact output bytes from the
  journal.
- Resize between output writes and observe `PTY_RESIZE` at the correct replay
  position.
- Retry the same input ID after reconnect and receive `DUPLICATE` without a
  second terminal write.
- Truncate the active record or compressed block and recover every preceding
  complete event.
- Rotate past the retention limit without stalling PTY reads and report a gap
  to a consumer behind the oldest available timestamp.
- Kill and restart `agentd` while Claude or Codex continues to run; discover the
  session, resume journal reading, and send new input.
- Replay output and resize events through `xterm.js` with the expected terminal
  behavior.
- Deny a configured path under Landlock to both the direct child and a spawned
  subprocess while allowing configured workspace and temporary paths.
- Pass the same protocol compatibility fixtures on Unix and Windows and build
  artifacts for the supported target matrix.

## Out of Scope

The initial host does not implement terminal emulation, ANSI parsing, a screen
model, semantic agent interpretation, central-server transport or
authentication, UI, multi-machine orchestration, or resource/network isolation
beyond the explicit Linux filesystem sandbox.
