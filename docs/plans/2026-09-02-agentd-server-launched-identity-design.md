# Server-Launched AgentD Identity

## Status

Approved on 2026-09-02.

This design supersedes the persistent AgentD identity and bootstrap credential
model in `2026-09-02-agentd.md`. AgentD does not register itself, keep a local
`AgentId`, or store a long-lived credential. Orion Server provisions every
agent record and starts every AgentD process through SSH.

## Goals

- Keep AgentD stateless with respect to identity and credentials.
- Store machine identity, launch configuration, and secrets on Orion Server.
- Let a live AgentD reconnect after transient transport failures without
  persisting a credential locally.
- Fence and terminate an obsolete AgentD before starting its replacement.
- Preserve native session-host processes and journals across AgentD restarts
  and updates.
- Use one configured local state root for session discovery and AgentD process
  exclusion.

## Non-goals

- AgentD self-registration or administrator approval of a self-generated key.
- A persistent AgentD key pair, PKCS#12 store, or local bootstrap token.
- Keeping an SSH connection open to supervise or monitor AgentD.
- Distributed launch coordination between multiple Orion Server instances.
  The first implementation runs one server instance.
- Artifact installation and remote digest enforcement. The launch design leaves
  room for a later SHA-256-verified update step.

## Selected Approach

Orion Server owns a stable `AgentId`. Each AgentD launch has a monotonically
increasing `generation`, a random `LaunchId`, and a single-use `LaunchPermit`.
The server passes the permit to a detached AgentD launch over a short-lived SSH
connection. AgentD exchanges the permit for a reconnect token, keeps that token
only in process memory, and uses it for later HTTP/2 reconnects.

The alternatives were rejected:

- A self-generated permanent key leaves durable access material on the machine
  and preserves a registration workflow that is unnecessary when the server
  controls every launch.
- A server-held PKCS#12 password adds remote-unlock and crash-safe password
  rotation without adding a useful trust anchor. SSH already authenticates the
  server that starts the process.

## Server-Side Agent Record

The durable server record contains:

- stable `AgentId` and server-owned display name;
- SSH endpoint and a reference to a server-side SSH credential;
- remote AgentD executable or image path;
- AgentD `stateDir`;
- desired AgentD version;
- startup and offline recovery timeouts;
- current launch `generation`, `LaunchId`, and lifecycle state;
- a hash and expiry for the current reconnect token, never the plaintext token.

The SSH host key must be verified. SSH private keys remain in the server's
protected credential store and are referenced rather than copied into the
ordinary agent record.

The first implementation has one Orion Server instance, so no distributed
lease is required. The component that owns recovery should still hide launch
ownership behind a narrow interface so a durable per-agent lease can be added
if the server later becomes active-active.

## Local Layout and Process Lock

The server passes one non-secret `stateDir` launch parameter. AgentD derives
all local paths from it:

```text
<stateDir>/
    agentd.lock
    sessions/
        <session-id>/
            metadata
            control endpoint or descriptor
            journal-*.seg
```

AgentD opens `<stateDir>/agentd.lock` without following symbolic links and
acquires an exclusive `FileChannel` lock before initializing transport or
session discovery. It keeps both the channel and `FileLock` alive until process
termination. A second AgentD exits with a distinct failure code if the lock is
held.

The lock file is not a presence marker and must not be unlinked as part of
normal startup or shutdown. The kernel releases the lock after orderly exit,
process failure, or forced termination. A stale file is harmless.

After acquiring the lock, AgentD writes non-secret diagnostic metadata such as
its PID, process start time, `LaunchId`, `generation`, and version. This
metadata assists recovery but is not proof of process identity. The state
directory and lock file must be owned by the account that runs AgentD and must
not be writable by unrelated users.

## Launch and Authentication Flow

An administrator first creates the agent record on Orion Server. There is no
AgentD-originated registration flow.

For a launch, restart, or update, the server performs these steps:

1. Move the agent to `RECOVERING`, increment `generation`, and revoke every
   credential from the previous generation.
2. Open a short-lived SSH connection to the configured machine.
3. Locate the previously recorded AgentD process and verify its PID, process
   start time, `LaunchId`, executable path, and local lock metadata before
   signalling it.
4. Send `TERM`, wait for the configured grace period, send `KILL` if necessary,
   and confirm that the old process no longer exists.
5. Create a cryptographically random, short-lived, single-use `LaunchPermit`
   bound to the `AgentId`, `generation`, and new `LaunchId`.
6. Start AgentD detached from the SSH session. Pass non-secret launch fields as
   ordinary parameters and the permit only through standard input or an
   anonymous inherited channel.
7. Close SSH without using it for process status, heartbeat, or logs.
8. Wait up to the startup timeout for AgentD to establish its outbound HTTP/2
   control connection.

The launcher must not put the permit in a command line, environment variable,
temporary file, diagnostic output, or log. Closing the SSH connection after
bootstrap must not terminate the detached AgentD.

The default startup timeout is 60 seconds. If AgentD does not connect in time,
the server marks the attempt `START_FAILED`, revokes its permit and generation,
and retries with bounded exponential backoff. Every retry repeats verified
termination before attempting another launch.

## HELLO, WELCOME, and Reconnect

The initial `HELLO` carries the server-assigned `AgentId`, `generation`,
`LaunchId`, `InstanceId`, protocol and agent versions, capabilities, and the
`LaunchPermit`. Over TLS, the server atomically verifies that the permit:

- matches the agent, generation, and launch;
- has not expired;
- has not previously been consumed.

Successful verification consumes the permit. `WELCOME` returns a random
reconnect token in addition to negotiated connection configuration. AgentD
keeps the plaintext token only in memory. The server persists its hash, expiry,
and generation so a server restart does not strand still-running agents.

The reconnect token is valid only for its launch generation. Its server-side
expiry advances while the authenticated control connection remains healthy and
permits reconnects during the offline recovery window. Changing generation
immediately invalidates the old token even if its time-based expiry has not
passed.

AgentD reconnects with exponential backoff and the in-memory token after a
short transport failure. It never writes the token to `stateDir`. Losing the
process therefore loses the token and requires a new server-controlled SSH
launch.

## Health and Recovery

SSH is not a monitoring channel. Orion Server determines availability from the
HTTP/2 control connection and AgentD heartbeats.

The default offline recovery timeout is 30 minutes and is configurable in the
server-side agent record. A lost connection shorter than the timeout leaves the
current generation valid so the same process can reconnect. At the timeout,
the server starts the recovery flow, revokes the generation, kills the old
AgentD over a new SSH connection, and starts a replacement.

If an old AgentD reconnects after its generation was revoked, the server rejects
it as obsolete. If the server cannot safely identify or confirm termination of
the old process, it must not start a second one. It records `RECOVERY_FAILED`
and reports an operator-visible diagnostic. SSH failures use bounded backoff
and do not alter native session-host processes.

The initial server lifecycle is:

```text
OFFLINE -> RECOVERING -> STARTING -> ONLINE
                |           |
                +-----------+-> START_FAILED
                |
                +--------------> RECOVERY_FAILED
```

## Session-Host Continuity

Native session-host processes do not depend on the AgentD process or its SSH
launcher remaining alive. Killing, replacing, or updating AgentD must not send
signals to session-host processes or their children.

At startup, AgentD scans `<stateDir>/sessions`, validates every session's
metadata, reconnects to live control endpoints, and reconstructs its in-memory
session registry. It then reports the complete discovered session set to the
server and resumes journal synchronization from the server's authoritative
cursors. Invalid or unreachable sessions are isolated and must not prevent
adoption of healthy sessions.

## Artifact Integrity Extension

A later update task should associate the desired AgentD version with a SHA-256
digest. Before launch, the server will upload into a versioned temporary path,
verify the remote digest, make the artifact non-writable by the AgentD runtime
account, and atomically switch the active version. SHA-1 is not suitable for
security-sensitive integrity checks.

This extension belongs in the short SSH deployment transaction and does not
change AgentD authentication or introduce persistent local credentials.

## Verification

Tests must cover:

- single-use permit success, expiry, replay, and binding to agent, generation,
  and launch;
- reconnect token success, expiry, server restart, and immediate generation
  revocation;
- lock acquisition, concurrent startup rejection, stale lock-file reuse, and
  lock release after process exit;
- redaction from arguments, environment, logs, diagnostics, and session files;
- startup timeout, bounded retries, SSH failure, verified `TERM`/`KILL`, and
  refusal to launch after ambiguous termination;
- an AgentD crash while session-host processes and journals continue;
- replacement AgentD discovery and adoption of all healthy existing sessions;
- server restart followed by reconnect of the same AgentD process;
- a short network interruption that reconnects the same generation and a long
  interruption that fences it and launches a replacement.
