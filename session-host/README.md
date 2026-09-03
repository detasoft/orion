# Orion Session Host

`session-host` is Orion's standalone native process and terminal owner. The
module freezes protocol v1, supplies compatibility fixtures, and hosts Unix
children through a real PTY. It records raw terminal and process events in one
ordered journal and serves input, resize, signal, terminate, and status commands
over a Unix-domain socket. The host and child remain alive when their launching
process exits. Windows ConPTY execution is added by a later task node.

## Platform Support

### Linux

Linux is the production Unix target. Before releasing the PTY child,
`session-host` makes itself a child subreaper with
`PR_SET_CHILD_SUBREAPER`. Descendants orphaned by double-fork or `setsid`
therefore reparent to the host instead of PID 1. The host combines subreaper
adoption with `/proc` discovery by parent, session, and PTY ownership. Tracked
PIDs include their process start time, which detects reuse between discovery
passes and reduces the risk of signaling an unrelated process. It does not
close the race between the final identity check and `kill(pid)`; the hardening
task below must use pidfd or an equivalent kernel-owned identity for that
guarantee.

This baseline is implemented, but production hardening remains tracked in
[Harden Linux process-tree
control](../docs/plans/current-work/native-session-host/linux-process-tree-control/TASK.md).
That task covers per-session cgroup v2 ownership where delegation is available,
pidfd-based lifecycle observation, removal of frequent system-wide `/proc`
scans, and acceptance tests for fast daemonization and forks racing with
termination.

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
Production process-tree guarantees apply only to Linux after the hardening task
above is accepted.

## Build

The exact hermetic Rust toolchain is pinned in `rust-toolchain.toml`. Direct
Cargo invocations use that file automatically, and the Makefile reads its
`channel` for Maven and Make builds. The `rust-version` in `Cargo.toml` is an
independent compatibility floor, not an exact build pin.

The Maven module downloads the pinned toolchain into
`.orion-cache/rust-toolchains` when one is not already present. It does not use
a globally installed `rustc` or Cargo. The Makefile bootstraps it with the
pinned Rustup 1.29.1 archive for the current host and verifies its checked-in
SHA-256 before the bootstrap executable receives permission to run.

```bash
mvn package -pl session-host
```

Maven stores each native build below
`session-host/target/native-resources/META-INF/orion/native/session-host/<target>`.
The `session-host-native` carrier JAR and the bootstrap executable JAR include
every target directory present there. On macOS the executable is a native
Mach-O binary for the host architecture. Release packaging targets x86_64 and
arm64 independently on Linux, macOS, and Windows.

Run Rust tests through Maven:

```bash
mvn test -pl session-host
```

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

## Idempotent Session Controls

Established-session `INPUT`, `RESIZE`, `SIGNAL`, and `TERMINATE` requests use
payload schema 2. AgentD assigns each operation a nonzero monotonic sequence
and supplies its CommandId, the exact opaque server CBOR command item, and the
typed effect bytes. The host compares all of those bytes for retry identity;
it never decodes or re-encodes the server command item.

Before applying an external effect, the live host durably writes
`COMMAND_ACCEPTED`. It then writes a durable `COMMAND_RESULT` and returns that
result event ID. A matching completed retry returns the original result ID
without repeating the effect. A matching pending retry reports that the
operation is still in progress. Conflicting or unexplained stale sequences are
rejected. `--max-unacknowledged-operations` bounds the live retry ledger and
defaults to 4096; server acknowledgement evicts covered completed entries but
does not lower the host's accepted-sequence high-water mark.

AgentD recovery uses the server's durably committed prefix plus the later
suffix in the still-running host journal. The host does not reconstruct a
failed incarnation, and AgentD keeps no private durable command cursor. After
the server durably commits a complete journal prefix, AgentD may send its event
ID through `ACK_JOURNAL`. The host atomically persists that monotonic watermark
beside the journal before requesting deletion. The sidecar is local deletion
permission only: it is not a journal record, an AgentD recovery cursor, or
evidence that the server committed anything by itself.
