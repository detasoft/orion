# AgentD Session Runtime and Control Design

## Goal

Launch native session hosts without tying their lifetime to AgentD, and expose
the checked-in native control protocol through typed Java results. A successful
launch hands ownership of the PTY, child process, journal, and control endpoint
to `session-host`; AgentD retains only recoverable coordinates.

## Runtime Boundary

`SessionRuntime` accepts a validated `SessionSpec` and returns a
`SessionLaunchResult`. The specification contains the Agent protocol session
ID, child arguments, terminal dimensions, sandbox selection, and a
`WorkspaceReference`. The workspace abstraction distinguishes an existing
directory from a future managed workspace. The MVP resolver accepts only an
existing directory. A managed reference and a non-empty arbitrary environment
return typed unsupported failures because the current native command-line
contract provides neither workspace preparation nor general environment
arguments. `TERM` and optional `COLORTERM` remain the terminal environment
supported by that contract.

`NativeRuntime` validates the executable, session ID, command, terminal
settings, workspace, and sandbox policy before mutation. It exclusively creates
`stateDirectory/sessions/<session-id>` and never adopts or overwrites an
existing directory. An injected `DetachedProcessLauncher` starts
`session-host` directly, redirects all three standard streams away from AgentD,
and returns a tentative process handle used only during initialization. The
host already ignores launcher `SIGHUP`; Java does not terminate children when
the launcher JVM exits. After handoff, the handle is discarded and neither
runtime close nor AgentD shutdown sends a signal.

Initialization uses one bounded deadline. It waits for a valid manifest through
the existing `SessionManifestReader`, for a present and readable journal through
the existing `JournalProbe`, and for a successful native `STATUS` response. It
does not decode the journal or read persisted lifecycle, journal bounds,
timestamps, or operation sequence. STATUS supplies liveness; equality between
its host PID and the manifest PID is only a correlation check. Protocol v1 has
no host-incarnation identifier, so PID reuse or endpoint replacement remains a
documented protocol gap.

Before handoff, only the process started by this attempt and the directory
exclusively created by it are eligible for cleanup. Cleanup first asks that
exact process to stop, waits for a bounded interval, and escalates only that
process if needed. The directory is removed only after the process is confirmed
exited or absent. If exit cannot be confirmed, launch returns a typed cleanup
failure and preserves the entire directory as potentially live durable state.

## Local Control

`SessionControlClient` hides endpoint transports and native framing. The Unix
implementation opens a short-lived nonblocking `SocketChannel` for each
request. A `Selector` enforces one whole-operation deadline across connect,
write, and read; no thread is allocated per I/O call. Frames are limited to 16
MiB, encoded little-endian with CRC-32C, and checked for fixed header fields,
response request ID, response kind, schema, flags, and payload shape.

Named-pipe endpoint selection uses the same transport factory, but returns a
typed unsupported result in this slice. The native Windows host and its timeout
capabilities are tracked separately, and the repository currently forbids
claiming Windows support.

Every command result retains the original Agent `CommandId` in memory. Native
v1's unsigned 64-bit request ID is correlation only and is reused only for an
exact retry. INPUT embeds and reuses the exact caller-provided input UUID and
bytes; reconnect after ambiguous delivery may safely resend that identical
frame because the host deduplicates the UUID for its lifetime. A duplicate
response is successful acceptance with the original journal timestamp.
RESIZE, SIGNAL, and TERMINATE are never automatically replayed after ambiguous
delivery because v1 cannot deduplicate their effects.

The future native `control-journal-idempotency` task adds the independent
AgentD-assigned `operationSequence`. This implementation neither synthesizes it
nor derives it from request IDs, command IDs, PIDs, metadata, timestamps,
journal event IDs, or filesystem order.

## Errors and Recovery

Expected validation, workspace, collision, initialization, host rejection,
unsupported transport, timeout, framing, connection, ambiguous-delivery, and
cleanup failures are values rather than ordinary control-flow exceptions.
Unexpected programming failures remain exceptions. A new request always opens
a new connection, so a previous broken connection cannot poison later control.
Host `ERROR` codes and bounded diagnostic details remain available to command
orchestration without crashing AgentD.

## Verification

Runtime tests cover successful durable handoff, invalid workspace and policy,
unsupported environment, existing session collision, early initialization
failure, missing journal, timeout cleanup, cleanup uncertainty preservation,
and absence of post-handoff termination. A launcher-process test verifies that
a fully redirected child remains alive after its launching JVM exits.

Control tests use real Unix-domain sockets to cover STATUS correlation, protocol
and framing failures, host `ERROR`, response timeout, reconnect, duplicate INPUT
with an identical frame, and no replay of an ambiguously delivered
non-idempotent command. Codec tests cover bounds, CRC, response IDs, and payload
validation.
