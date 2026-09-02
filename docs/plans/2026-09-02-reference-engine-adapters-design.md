# Reference Git Engine Adapters Design

## Goal

Provide reusable JGit and canonical Git client/server adapters for the shared
Git interoperability harness, with deterministic repositories, bounded process
lifecycles, and actionable engine diagnostics.

## Architecture

Keep the adapters in `tests/git-engine-test-support`, alongside the shared
workflow contracts. `GitClients` and a new `GitServers` factory expose the two
reference engines without leaking their concrete implementation details to
scenario code.

Rename `NativeGitWorkflowClient` to `GitCliWorkflowClient`. A package-private
command runner centralizes canonical Git configuration, environment isolation,
timeouts, output capture, and version discovery. The JGit client continues to
use JGit porcelain directly. Both clients implement the local ref updates and
multi-ref pushes needed by the shared workflow capability surface.

`JGitDaemonServer` wraps JGit `Daemon` on loopback port zero and enables
receive-pack. `GitDaemonServer` provisions repositories below an isolated base
path and starts `git daemon` on a dynamically selected loopback port. Because a
closed port reservation is inherently racy, startup uses a bounded number of
fresh OS-selected ports, readiness probes, early-exit detection, and retained
diagnostics from every failed attempt.

## Determinism and Isolation

Both clients use `main`, the shared parity identity, the epoch commit instant,
UTC, LF line endings, disabled commit signing, and stable file-mode behavior.
Canonical Git commands ignore system and user-level configuration through
per-process environment and command configuration; no command reads or writes
global Git configuration.

Each server accepts only repositories provisioned beneath its invocation root.
Canonical daemon state and logs live beneath that root. All listener addresses
are loopback-only and all ports are selected dynamically.

## Failure Handling

Canonical Git commands have whole-process deadlines. A timeout requests normal
termination, waits for a bounded grace period, then forcibly terminates the
process and reports the command and captured output. Daemon readiness and
shutdown are bounded separately. No thread is allocated merely to simulate an
I/O timeout.

Clients and servers expose concise engine diagnostics. The interoperability
harness augments scenario failures with the JGit and canonical `git --version`
values, while command and daemon failures also include their captured process
diagnostics.

## Testing

Focused tests cover deterministic commits and ref updates, missing canonical
Git as a clear prerequisite failure, command configuration isolation, daemon
dynamic ports and repository-root isolation, and failure diagnostics. A shared
smoke scenario performs initial commit, push, clone, fetch, fast-forward pull,
ref update, and multi-ref push across JGit/JGit, JGit/Git, Git/JGit, and Git/Git.
