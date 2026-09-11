# Build the Native Session Host for the Agent Harness

Status: todo

Create a standalone Rust `session-host` that owns an interactive PTY/ConPTY
child, persists a durable ordered session journal, accepts local control
commands, and survives independently of `agentd`.

## Scope

- Preserve raw terminal bytes and resize history for deterministic replay.
- Keep one host-assigned order across terminal, process, and harness events.
- Support recovery, bounded retention, session discovery, and control reconnects.
- Restrict the Linux child process tree with an optional Landlock policy whose
  rule failures are fail-closed and whose capability mismatch is non-fatal.
- Publish stable journal and control compatibility fixtures for external
  consumers such as `agentd`.
- Keep terminal emulation, central-server transport, and semantic agent models
  outside the host.

---

## Native Session Host

Journal format amendment:
the session journal contract embedded below
supersedes the journal framing, cursor, segmentation, compression, and
retention requirements in this plan.

### Context

Orion needs a local execution primitive for interactive agent CLIs such as
Claude, Codex, and shells. The process that owns an interactive command must not
share `agentd`'s lifecycle: restarting or temporarily stopping `agentd` must not
terminate the command or lose terminal history.

Implement `session-host` as a small standalone Rust executable. It owns the
terminal, child process, journal, sandbox policy, control endpoint, and session
lifecycle. `agentd` discovers hosts and session directories, reads journals,
and sends commands, but never owns a hosted child process.

The Maven bootstrap executable JAR carries native session-host resources under
`META-INF/orion/native/session-host/<target>/`. The production release matrix
contributes Linux and Windows builds for x86_64 and aarch64 to that resource
tree. macOS builds are development and diagnostic artifacts only.

### System Boundary

```text
agentd -- control command --> session-host -- PTY/ConPTY --> child tree
             |                    |
             +-- reads -----------+-- append-only session journal
```

The host is not a terminal emulator, network proxy, central-server client, or
semantic interpreter for agent output. ANSI and VT data remains opaque. Higher
level conversation, tool, artifact, and screen models are built above the
journal.

### Invariants

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

### Versioned Contracts

The first task must freeze and document binary test vectors for the journal and
control protocol before dependent implementations rely on them.

The journal contract includes:

- fixed segment, block, and record framing with magic values, format versions,
  flags, lengths, timestamps, counts, codec identifiers, and checksums;
- event-type namespaces for journal/system, terminal, process, and harness
  events;
- MVP payload schemas for `PTY_OUTPUT`, `PTY_INPUT`, `PTY_RESIZE`,
  `PROCESS_STARTED`, `PROCESS_EXITED`, and `SIGNAL`;
- opaque unknown event types and versioned schemas that consumers may leave
  uninterpreted without stopping journal iteration;
- raw binary payloads for terminal output and an input ID in `PTY_INPUT`;
- detection of partial records and blocks, with an unfinished tail ignored;
- cursor and gap semantics based on record timestamps.

The control contract includes `INPUT`, `RESIZE`, `SIGNAL`, `TERMINATE`,
`ACK_JOURNAL`, `STATUS`, and generic typed-event submission. Commands receive
`RECEIVED` admission responses; queries and failures use their typed response or
`ERROR`. `SERVER` operation sequences provide host-incarnation replay
protection for effect commands, while `MANUAL` sequences are live
response-correlation values and every valid manual command executes. Input IDs
remain journal data and do not provide another deduplication mechanism. The
protocol must not expose Unix-domain-socket or named-pipe details.

The implemented `ACK_JOURNAL` is an EventId-only monotonic retention control. It
updates the existing sidecar without a command source, operation sequence,
envelope, or journaled `COMMAND_RESULT`. The schema-1 `CLAIM_SERVER_CONTROL`
contract is still superseded by the active AgentD
[command-orchestration](../04_agentd/04_command-orchestration.md) task, which
will own a sequence-independent connection fence.

### Session Storage

Each session uses one directory:

```text
sessions/<session-id>/
    metadata
    control endpoint or endpoint descriptor
    00000001.cbor.zst
    00000002.cbor
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

### Process and Platform Behavior

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
size. Input is ordered by admitting its source and operation sequence,
appending `PTY_INPUT`, and then writing its bytes to the terminal. Server replay
protection comes only from the sequence high-water mark; manual delivery has no
deduplication or durable retry identity.

The host records process start and exit, including exit code and termination
signal when available, finalizes the journal, and exits immediately or after a
configurable grace period. Its normal operation must not depend on the process
that launched it remaining alive.

### Linux Sandbox

When requested, the Linux child setup applies `no_new_privs` and Landlock after
fork/spawn preparation and before `exec`. A policy contains read-write and
read-only paths and is inherited by all descendants. The host retains access to
its own session files and IPC.

If the kernel cannot enforce a requested policy, the default is fail-closed.
An explicit configuration may allow unsandboxed execution. CPU, memory,
process, network, seccomp, cgroup, and namespace isolation remain future policy
providers and must not alter terminal or journal protocols.

### Delivery Order

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
[AgentD](../04_agentd/TASK.md).

### MVP Boundary

The first usable host slice comprises tasks 1-6 on Linux, with best-effort
macOS PTY support for local development where the Unix abstraction permits it.
It must run an interactive shell or agent, accept input and resize commands,
preserve ordered raw terminal history, survive control-client loss, expose
retention gaps, and enforce an inherited Landlock policy when requested.
AgentD restart and network-resume acceptance belongs to the separate AgentD
plan.

Windows parity follows without changing the v1 logical protocols. Required
production artifacts are Linux and Windows for both x86_64 and aarch64. macOS
x86_64 and aarch64 artifacts remain development-only because an unprivileged
host cannot guarantee ownership of fully daemonized descendants.

### Acceptance Scenarios

- Run `bash`, send `echo hello`, and reconstruct the exact output bytes from the
  journal.
- Resize between output writes and observe `PTY_RESIZE` at the correct replay
  position.
- Repeat a `SERVER` sequence and receive a stale rejection without a second
  effect; repeat a `MANUAL` sequence and execute the effect again.
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

### Out of Scope

The initial host does not implement terminal emulation, ANSI parsing, a screen
model, semantic agent interpretation, central-server transport or
authentication, UI, multi-machine orchestration, or resource/network isolation
beyond the explicit Linux filesystem sandbox.

---

## Session Journal CBOR Sequence Format

### Decision

Store each session journal as numbered segments containing a CBOR Sequence.
Each segment is a sequence of independent CBOR items without a segment header,
block header, length prefix, or framing outside CBOR itself.

The format has no `FINAL` flag or equivalent completion marker. A record is
complete exactly when its CBOR item is complete; writers and readers must not
reintroduce a separate persisted completion state under another name.

This document supersedes the journal framing, timestamp, cursor, segmentation,
compression, retention, and crash-tail requirements in
`2026-09-01-native-session-host.md`. The remaining session-host architecture
and process-host requirements continue to apply.

### Record Format

The base record is a CBOR array:

```text
[eventId, eventType, payload]
```

- `eventId` is an unsigned 64-bit integer that is unique and strictly
  increasing within one host incarnation.
- `eventType` is an integer event-type identifier.
- `payload` is a CBOR value whose schema is selected by `eventType`.

The initial event payloads include:

```text
PTY_OUTPUT:     [eventId, PTY_OUTPUT, byte-string]
PTY_RESIZE:     [eventId, PTY_RESIZE, [cols, rows]]
PTY_INPUT:      [eventId, PTY_INPUT, [ptyInputId, byte-string]]
PROCESS_EXITED: [eventId, PROCESS_EXITED, [exitCode]]
```

The contract work must assign and freeze stable integer IDs for the supported
event types and publish golden encoded fixtures. Terminal bytes remain opaque
and are stored without text conversion.

### Event ID

Separate journal `sequence` and `timestamp` fields are removed. One `eventId`
serves as record identity, total order, approximate session-relative time, and
journal cursor.

Generate it from a monotonic clock relative to the session start:

```text
raw = monotonicTimeSinceSessionStart()
eventId = max(raw, previousEventId + 1)
```

The value is not a Unix timestamp and must not be compared between host
incarnations. A host incarnation creates one writer, and a failed writer is
never reopened for append.

### Segment Layout and Rotation

A session journal uses monotonically numbered files:

```text
session/
    00000001.cbor
    00000002.cbor
    00000003.cbor
```

The file name establishes segment order and does not encode an event ID. Each
file contains `CBOR Sequence<SessionEvent>`, and its first item is an ordinary
session event.

The active session appends to the current `.cbor` segment. After the configured
size threshold is reached, the writer closes it and creates the next numbered
segment. A CBOR item is never split between segments, and every new non-empty
segment begins with one complete record.

### Durability Boundaries

The writer exposes the stable-storage requirement at each operation. Buffered
appends write ordinary high-volume records without synchronizing the active
file per record. A buffered record can therefore remain only in the active
segment and can be part of an incomplete crash tail.

The nearest pre-existing ancestor of a requested session path is the durable
root assumed by the writer. Before relying on it, startup synchronizes its
parent to revalidate the ancestor's directory entry. It then creates every
missing descendant one component at a time and synchronizes its parent before
proceeding, so failed attempts and concurrent creation cannot leave an
unpublished entry that a retry accepts as durable. The session directory itself
is durably reachable before a segment is published.

Every newly created segment is published by synchronizing the journal
directory. Before rotation publishes its successor, the writer synchronizes
the complete closed segment. Segment-boundary synchronization is required even
when the append that triggers rotation is buffered.

A durable append writes its authority record and synchronizes the active
segment before accepting the event ID. Success means that the complete journal
prefix through that record is durable across a machine crash, including any
buffered records that precede it. A failed synchronization rolls the record
back durably or makes the writer unavailable for further appends.

Normal host completion uses a durable finish operation as the sole writer of
`PROCESS_EXITED`. If the final record rotates, the writer synchronizes the old
prefix, publishes the new segment, writes the exit record, and synchronizes
that record before returning. Maintenance compression and acknowledged
retention remain separate from these record durability barriers.

### Segment Ranges and Reading

The journal is self-indexing. A reader determines a segment's start by decoding
its first complete item and extracting `firstEventId`.

A cursor reader must:

1. List journal segments in segment-number order.
2. Decode the first record of every segment.
3. Find the last segment whose `firstEventId` is less than or equal to the
   requested ID.
4. Start with that segment, or with the oldest segment when no such segment
   exists.
5. Skip records whose `eventId` is less than or equal to the requested ID.
6. Return every later record and continue through following segments.

If the requested ID is lower than the first available event ID, the reader
reports a retention gap rather than an ordinary empty result.

### Index and Source of Truth

Persistent indexes are not required for correctness. An implementation may
cache the mapping from segment number to `firstEventId` when segment counts
justify it, but that index:

- may be deleted or damaged;
- must be rebuildable from the segment files;
- must never override values decoded from segment contents;
- is not required to open or read the journal.

The segment files are the only mandatory source of truth for the first and last
available events, segment ranges, record order, and cursor behavior. Separate
timestamp, sequence, cursor, and first/last event metadata is not journal state.

### Retention

Journal maximum size and retention policy are configurable. When the limit is
exceeded, delete the oldest fully closed segments without blocking writes to
the active segment.

After deletion, `firstAvailableEventId` is obtained from the first record of
the oldest remaining segment. Readers whose cursor predates that value receive
a gap result.

Acknowledgement grants durable deletion permission but does not make physical
deletion part of ACK completion. The single journal-maintenance worker
coalesces non-waiting wakes to the greatest acknowledged watermark and
active-segment boundary, then retries failed cleanup on a later wake or host
finish. Journal appends and unrelated controls do not wait for discovery,
compression, retention scanning, or deletion.

Retention first totals physical segment sizes with checked arithmetic. It
decodes no records when the journal is already within its target or no durable
acknowledgement exists. When oversized, it bounded-stream-decodes only the
oldest closed deletion candidates, preserves their segment and event order,
and stops at the size target, acknowledgement boundary, or active-segment
boundary. A greatest-observed active-segment boundary may conservatively retain
an extra closed segment but cannot expose the writer's current segment to
deletion. Validation of later noncandidate segments is deferred until a reader
or later retention attempt needs them.

### Compression

Compression applies only to closed segments:

```text
00000001.cbor.zst
00000002.cbor.zst
00000003.cbor
```

Decompressing a closed segment must produce the same logical CBOR Sequence.
Compression must not add logical journal records or change record encoding. To
discover `firstEventId`, a reader only needs to decompress enough data for the
first complete CBOR item.

The active segment remains uncompressed. Replacement of a closed `.cbor` file
with its `.cbor.zst` form must be reconciled without losing the only valid
copy.

### Crash Tails

Abnormal termination may leave the active `.cbor` segment ending in a partial
CBOR item. Readers and validation must accept every preceding complete item,
ignore the incomplete trailing item, and must not classify the whole segment
as corrupt. No flag written before or after an item participates in this
decision. The abandoned active segment is never reopened for append.

Corruption inside an already completed item or before the trailing item remains
a journal error and must not be silently treated as an incomplete tail.

### Forward Compatibility

Future records may append fields:

```text
[eventId, eventType, payload, ...optionalFutureFields]
```

The minimum record length is three. Readers must interpret the first three
positions, ignore unknown trailing positions, and preserve record boundaries.
Readers must surface an unknown `eventType` and its payload as an opaque record
and continue with later records. Consumers may skip semantic interpretation of
that opaque record. New fields may only be appended; the meaning of existing
positions cannot change.

### Replication

The same logical CBOR Sequence is used when AgentD replicates a session journal
to the server:

```text
session-host -> CBOR segments -> agentd -> HTTP/2 stream -> CBOR Sequence -> server
```

AgentD should preserve original encoded records where practical and must not
require conversion to another event format. Transport-level chunking may split
bytes arbitrarily, but it cannot change CBOR item boundaries or logical record
contents after reassembly.

### Verification

The format change is complete when tests cover:

- golden bytes and round trips for the required event payloads;
- monotonic event IDs when clock readings repeat within one host incarnation;
- multiple items without external framing and rotation only between items;
- cursor reading before, within, and between segments;
- gap reporting after retention deletes old segments;
- rebuilding an optional index from uncompressed and compressed segments;
- discovery of the first event without decompressing an entire large segment;
- a partial active tail accepted through the last complete boundary;
- absence of `FINAL` or any equivalent persisted completion marker in encoded
  fixtures and writer output;
- additional trailing fields and unknown event types;
- unchanged logical records across session-host, AgentD, and server fixtures.

No mandatory segment metadata file, timestamp index, sequence index, cursor
index, or persistent first/last event metadata may be required for correctness.
