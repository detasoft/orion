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
