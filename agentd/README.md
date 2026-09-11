# AgentD

## Control runtime

`Agent.create` assembles the production control runtime around one process lock,
one session-discovery registry, and one authenticated HTTP/2 control lifecycle.
Startup acquires the process lock before discovery or network activity begins.
If startup fails, already-started services are closed in reverse order; normal
shutdown likewise stops reconnect and heartbeat work, closes the transport and
discovery monitor, and only then releases the process lock.

The initial connection authenticates with the launch permit. After the server
accepts it, AgentD keeps the returned reconnect token only in process memory and
uses it for bounded-backoff reconnects and periodic heartbeats. A rejected
initial credential fails startup. A rejected or revoked reconnect remains
offline and retries until AgentD is closed or replaced by a newly launched
generation.

Session discovery continues while the server is unavailable. After reconnect,
the server requests a full session list and AgentD responds from the latest
completed discovery snapshot, so changes found offline are reconciled. Closing
or replacing AgentD does not terminate existing `session-host` processes or
alter their journals.

The control connection uses HTTPS with the configured server URI. Certificate
trust and hostname verification are performed by the production TLS client;
there is no plaintext or trust-all control path.

## Local terminal

AgentD can start a local native session and attach the invoking terminal:

```text
java -jar agentd.jar terminal start \
  --state-dir /path/to/state \
  [--session-id ID] [--cwd PATH] -- COMMAND...
```

AgentD installs its bundled native `session-host` at
`<state-dir>/runtime/session-host`. It reuses a matching installation and
atomically replaces one whose bundled-resource timestamp and SHA-256 differ.
Pass `--session-host /path/to/session-host` to override the bundled executable.

After the native host publishes its durable session directory, `start` prints
that directory and enters the same attach path as:

```text
java -jar agentd.jar terminal attach --session-dir /path/to/session
```

Attach replays retained terminal output from the beginning and then follows new
journal records. It does not persist a cursor or acknowledge journal records,
so every fresh attach replays the retained output again.

Press `Ctrl-]` followed by `d` to detach. Press `Ctrl-]` twice to send one
literal `Ctrl-]` byte. Detaching restores the local terminal and leaves
`session-host` and its child process tree running; the printed session directory
can be attached again later.

On normal process completion the command returns the recorded child exit code
from 0 through 255. A local detach returns 0, command-line usage errors return
2, and terminal, session, journal, or native-control failures return 1.

Local terminal attachment currently requires macOS or Linux and an available
POSIX controlling terminal at `/dev/tty`. The independent `session-host`
continues to own the PTY, child processes, journal, and control execution; an
Orion server is not involved in this local path.
