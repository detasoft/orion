# AgentD Replacement and Recovery Design

## Goal

Reconcile one remotely provisioned AgentD process safely: identify and stop only
the recorded AgentD, preserve its native session-host descendants, recover from
partial launch/version commits, and bound offline detection, startup, termination,
and retry delays.

This leaf extends `agent-provisioning`. Durable machine configuration, permit and
generation revocation, `orion.xml`, administration UI, and the concrete control-plane
availability observer remain in the administration and end-to-end leaf.

## Selected Approach

Add a synchronous `RemoteAgentdReconciler` above the existing verified SSH operation
and runtime bundle installer. The reconciler receives an availability boundary, a
source of fresh launch attempts, and an injectable clock/sleeper. It waits for a
sustained offline interval, reconciles remote process state, and waits for the exact
new launch to become online. Failed startup attempts use fresh launch IDs and permits
and bounded exponential backoff.

The alternatives are less suitable:

- A long-lived remote supervisor would simplify PID tracking but add an unrequested
  service, update protocol, and privilege boundary.
- Asking AgentD to stop over its control connection cannot recover a disconnected or
  wedged process and does not prove operating-system process identity.

## Identity Evidence

`AgentProcessLock` remains a kernel lock with process-owned diagnostic metadata, not
a presence marker. Its owner-only regular file metadata is extended to include the
exact normalized executable path in addition to PID, start epoch, launch ID, and
generation. Recovery never treats the file alone as proof that the process is alive.

Each successful detached launch also creates an owner-only, generation/launch-scoped
identity record below the install root. It contains:

- PID;
- launcher-observed platform-native process start token;
- launch ID and generation;
- exact immutable release and AgentD executable paths.

The record is written through a mode-`0600` temporary regular file and renamed into
place. Its directory must be an owner-only, non-symbolic directory. The launcher
obtains the start token from `/proc/<pid>/stat` on Linux and the widest stable process
start representation available from `ps` on macOS. The token is opaque to Java and
is compared byte-for-byte on every later probe. `ProcessHandle` epoch time remains
diagnostic corroboration, not the native identity token.

Before trusting a record, reconciliation checks that the state directory, lock file,
identity directory, and identity file are not symbolic, have the expected type, are
owned by the SSH account, and are not group- or world-accessible. It parses a bounded
ASCII response with exact fields and rejects duplicates, missing fields, controls,
and unexpected versions.

## Signal Safety

Identity inspection and each signal are one remote shell transaction. Immediately
before `TERM`, and again immediately before `KILL`, the transaction re-reads the
generation record and lock metadata and compares PID, native start token, executable,
release, launch ID, and generation with the server-recorded identity. A disagreement,
unsafe file, unreadable field, or inability to inspect the process produces a typed
uncertain-identity or privilege failure and sends no signal.

Termination polling addresses only the recorded PID. If the PID disappears, the old
AgentD is terminated. If its native start token changes, the recorded process is also
considered gone and the reused PID is never signalled. `TERM` receives a grace bound;
`KILL` receives a separate confirmation bound. Replacement launch is forbidden until
termination is confirmed.

Signals are sent to the AgentD PID only, never a process group, child list, session,
or executable-name match. This deliberately leaves already-launched session-host
processes and their descendants running. POSIX cannot provide the absolute exclusion
of a pidfd-backed helper; the implementation therefore uses the narrowest available
immediate full recheck and fails closed whenever identity is uncertain.

## Crash-Recovery Protocol

Immutable verified releases remain the deployment substrate and `current` remains
the sole atomic version commit point. Process identity publication is generation
scoped state, not a second version commit point.

For each desired launch, reconciliation follows these rules:

1. Inspect `current`, the requested launch record, `agentd.lock`, and the live PID.
2. If the exact requested launch is live, adopt it. If `current` still names the old
   release, atomically complete the `current` switch and resume waiting for ONLINE.
3. If a verified or staged release exists without a matching live process, reuse or
   safely replace only that launch's staging state and retry launch.
4. If an old recorded AgentD is live, verify and terminate it before launching.
5. If live state is unknown, mismatched, malformed, unsafe, or inconsistent, fail
   closed without launching or signalling.
6. Launch the target with its permit on SSH channel input, capture its PID and native
   start token, wait for process-owned lock metadata for that exact launch, publish
   its generation record, then atomically switch `current`.

A crash before identity publication is recoverable when the live PID, process-owned
lock metadata, exact executable, requested launch ID, and generation prove the target;
reconciliation reconstructs the generation record and completes the commit. A crash
after identity publication but before `current` switch adopts the same proven target
and completes the switch. A matching `current` and record resumes ONLINE waiting.
Any other `current`/record disagreement fails closed.

## Offline, Startup, and Retry State

The availability boundary has two duration-bearing operations: wait until the agent
has remained offline for the configured recovery timeout, and wait until an exact
launch is online for the startup timeout. The later control-plane leaf implements
these waits from authenticated control state; this leaf owns their configuration and
state-machine use.

The reconciler distinguishes:

- `WAITING_OFFLINE`: the existing generation may still reconnect;
- `TERMINATING_OLD`: the recorded old AgentD still needs verified termination;
- `AWAITING_ONLINE`: the requested replacement is already launched and must not be
  stopped merely because ONLINE is delayed;
- `RETRY_DELAY`: a startup attempt failed and its generation has been revoked by the
  caller; the next supplied attempt must have a fresh launch ID and permit.

Backoff doubles from an initial positive duration, is capped at a configured maximum,
and stops at a configured attempt count. Before a fresh retry launch, reconciliation
first treats the prior launched attempt as the recorded old process and performs the
same verified termination sequence. No dedicated thread enforces a timeout: SSH uses
its existing native deadlines and shared operation watchdog, while availability and
sleeping are injected duration-aware boundaries.

## Activation and Failure Handling

Release installation remains idempotent and integrity checked. The old `current`
target is preserved on identity uncertainty, termination failure, launch failure, or
failure to prove the process-owned lock metadata. Once the exact replacement is
proven, `current.next-<launchId>` is atomically renamed to `current`. A retry may
complete this commit without launching a duplicate.

Failures distinguish unsafe or malformed identity, identity mismatch, insufficient
privilege, termination confirmation timeout, startup timeout, and retry exhaustion.
Diagnostics include the phase and non-secret PID/path/launch information plus already
bounded remote stderr where useful. Permits remain absent from arguments, environment,
files, logs, captured output, and exceptions.

## Verification

Contract and state-machine tests cover positive durations, capped exponential
backoff, sustained-offline behavior, delayed ONLINE without duplicate launch, fresh
attempts after startup timeout, and retry exhaustion.

Live SSH/POSIX tests cover successful replacement and atomic version transition,
adoption after crashes before and after the `current` switch, identity mismatch with
no signal, malformed/unsafe/symbolic identity state, permission denial, PID reuse
before `KILL`, termination timeout preventing launch, and a session-host sentinel that
survives replacement. Existing tests continue to prove host-key verification,
integrity checking, detached launch, permit redaction, and shared-watchdog timeouts.
