# Orion Session Host

`session-host` is Orion's standalone native process and terminal owner. The
module freezes protocol v1, supplies compatibility fixtures, and hosts Unix
children through a real PTY. It records raw terminal and process events in one
ordered journal and serves input, resize, signal, terminate, and status commands
over a Unix-domain socket. The host and child remain alive when their launching
process exits. Windows ConPTY execution is added by a later task node.

## Platform Support

### Linux

Linux is the production Unix target. The host sets and verifies child-subreaper
mode before releasing the held PTY child. When cgroup v2 delegation supplies
`cgroup.procs`, `cgroup.events`, and `cgroup.kill`, the child enters a
dedicated session cgroup before exec. The host retains pidfds for safe signal
delivery and observes cgroup population without scanning all processes.

If cgroup setup is unavailable, a durable `HOST_WARNING / CGROUP_FALLBACK`
records the reason before child release. The fallback uses retained pidfds,
subreaper adoption, and process discovery with post-acquisition identity checks.
It still scans `/proc` on explicit controls and after root exit, and requires
stable empty observations plus no unreaped children before shutdown.

Graceful TERMINATE signals the currently owned processes once. Forced TERMINATE
uses one `cgroup.kill` write when available, or signals the current fallback
set once. Timing, retries, and escalation belong to callers. Descendants that
fork after a fallback signal require another explicit control. Foreground
signals are restricted to owned pidfds in the observed foreground group.
See the [reconciliation notes](../docs/plans/tasks/05_native-session-host/01_linux-process-tree-control.md).

### macOS

macOS support is for local development only. PTY execution, journal replay,
input, resize, foreground-process-group signals, and ordinary termination are
available, but complete descendant ownership is best effort.

Unlike Linux, unprivileged macOS has no child-subreaper facility. Its supported
`EVFILT_PROC` fork notification does not expose the child PID, while
`NOTE_TRACK` and `NOTE_CHILD` have been unsupported since macOS 10.5. A process
that quickly double-forks, calls `setsid`, closes the PTY, and reparents to
`launchd` can therefore escape discovery and survive `TERMINATE`. Reliably
tracking that case requires a privileged EndpointSecurity component with the
corresponding Apple entitlement; it cannot be guaranteed by the standalone
unprivileged host.

Do not use the macOS implementation as a process-isolation or cleanup boundary.
Long-lived detached processes also make its current libproc fallback expensive,
because discovery may enumerate system processes and their file descriptors.
Production process-tree guarantees apply only to Linux.

## Build

The exact Rust toolchain is pinned in `rust-toolchain.toml`. Maven invokes the
installed `cargo` executable, and Rustup selects the pinned toolchain from that
file. The `rust-version` in `Cargo.toml` is an independent compatibility floor,
not an exact build pin.

```bash
mvn package -pl session-host
```

Maven stores Cargo outputs below `session-host/target/cargo` and copies the
selected executable to
`session-host/target/classes/META-INF/orion/native/session-host/<target>`.
The `session-host` carrier JAR and the bootstrap executable JAR therefore
include the native executable. The `dist` profile uses Cargo's release profile:

```bash
mvn package -Pdist -pl core/bootstrap -am
```

Run Rust tests through Maven:

```bash
mvn test -pl session-host
```

Apache Maven Build Cache treats every checked-in file below `session-host` as
an input and skips unchanged Cargo work. An invocation containing `clean`
always runs the Rust tests again. Cargo compiler incrementality is independent
and can be disabled with `-Drust.incremental=false`.

The independently released `pro.deta.maven:rust-maven-plugin` is resolved from
GitHub Packages. Maven settings must provide GitHub Packages credentials under
server id `github`. For local plugin development, install its snapshot from
`build-tools/rust-maven-plugin` and select it with
`-Drust-maven-plugin.version=0.1.0-SNAPSHOT`.

Regenerate checked-in protocol fixtures after an intentional protocol
change:

```bash
make session-host-fixtures
```

The journal fixtures are shared with AgentD and server-side consumers. Any
future change to their version-1 bytes requires an intentional compatibility
decision.

## Journal Storage Limits

`--journal-segment-bytes` sets the uncompressed target size for each journal
segment and defaults to 67,108,864 bytes (64 MiB). `--journal-max-bytes` limits
the physical size of the retained journal and defaults to 1,073,741,824 bytes
(1 GiB). Both values are positive decimal byte counts, and the journal maximum
must be at least the segment target.

The host rotates only between complete CBOR items, so one oversized event stays
whole and may exceed the segment target. Closed segments are compressed without
waiting for server acknowledgement. Physical deletion is different: the host
deletes only a size-selected oldest prefix whose complete events are covered by
the durable `control-retention-state` watermark. With no watermark, or while
server acknowledgement lags, the journal may remain above its configured
maximum indefinitely. The active raw segment is never deleted. Readers behind
a deleted prefix receive a retention gap whose floor is the first event in the
oldest remaining segment.

## Session Controls

Established-session `INPUT`, `RESIZE`, `SIGNAL`, `TERMINATE`, and `ACK_JOURNAL`
requests use payload schema 2. A control client supplies a nonzero monotonic
sequence in the frame header, the exact opaque server CBOR command item, and
the typed effect bytes. The host never decodes or re-encodes the server command item. A sequence
at or below the accepted-sequence high-water mark is stale,
so a reconnect cannot repeat an already accepted effect.

The host applies each new effect synchronously and writes one `COMMAND_RESULT`
with an empty detail on success or diagnostic detail on an application failure.
After a new operation passes the high-water mark, the host returns `RECEIVED`
with the sequence in the response header and then applies the effect. This is
an admission receipt, not a durable journal confirmation; a client observes
the result in the journal when its append succeeds. There is no separate
durable acceptance record. Validation and stale-sequence
failures return `RECEIVED` with an error payload. If a journal append
fails, the host reports it only on stderr and does not retry the effect or the
append.

The sequence identifies an operation and protects it from replay; it does not
define FIFO order across control connections. Ordinary effects are mutually
exclusive, but `TERMINATE` bypasses the effect mutex so it can signal descendants
while a blocked ordinary effect is still running. The order in which different
connections acquire the effect mutex is not preserved in the sequence.
`RECEIVED` may precede an earlier effect or result, so journal readers match
`COMMAND_RESULT` records by sequence rather than by their physical record order.

PTY output remains continuously drained when a `PTY_OUTPUT` append fails. The
host reports the failure on stderr and attempts later output chunks again, so a
transient writer failure may recover. If the writer cannot recover, affected
output is discarded and the journal is incomplete; the host still keeps the
child and PTY service running.

`COMMAND_RESULT` describes the host's execution attempt, not a rollback
boundary. `Failed` does not imply that no side effect occurred: `INPUT` may
have transferred only part of the requested bytes, and a signal may have been
delivered to some or none of its targets. The journal's `PTY_INPUT` record
contains the requested bytes, not a delivery acknowledgement. If no
`COMMAND_RESULT` exists, the effect is unknown because the host may have
executed it before the journal append failed. Retrying with a new sequence is
a new attempt and may repeat an earlier partial effect.

Planned AgentD recovery uses the server's durably committed prefix plus the
later suffix in the still-running host journal. Recorded sequences do not
reveal admissions whose result is pending or missing; sequence allocation on
reconnect remains an AgentD integration concern. The host does not reconstruct
a failed incarnation. After the server durably commits a complete journal
prefix, a client may send its event ID through the `ACK_JOURNAL` operation.
The host atomically persists that monotonic watermark beside the journal before
requesting deletion. The sidecar
is local deletion permission only: it is not an AgentD recovery cursor or
evidence that the server committed anything by itself. ACK follows the ordinary
operation result path and creates a `COMMAND_RESULT` when the journal is writable.

The current Java control client still uses a different operation wrapper and
response model, and has no `ACK_JOURNAL` command. See the
[implementation comparison](../docs/plans/tasks/04_agentd/03_session-host-contract-alignment/TASK.md)
for the current boundary and pending integration work.
