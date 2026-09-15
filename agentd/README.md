# AgentD

## Run from the checkout

With Orion running and your administrator SSH key enrolled (`make enroll-admin-key`),
start AgentD in another terminal:

```sh
make run-agent
make run-agent AGENT_ARGS='--help'
```

The Make goal builds the reactor dependencies and runs `LocalAgentMain` through
`exec:java`. It requests a fresh launch authorization from the running server
through the existing public-key SSH administrator connection, then starts the
ordinary AgentD runtime in the foreground. The permit stays in memory and is
never passed in process arguments, written to a file, or printed by the launcher.
Every new invocation obtains a fresh generation, launch ID, and single-use permit.

Defaults are SSH `root@localhost:8022`, control `https://localhost:8443`, agent label
`local`, and owner-only local state under `orion_root/agentd-local`. Override these
through `AGENT_ARGS`, for example:

```sh
make run-agent AGENT_ARGS="--ssh-port 9022 --server https://localhost:9443 --state-dir '/tmp/local agent'"
```

`--ssh-option` passes one additional `ssh -o` option, such as `IdentityFile=/path/to/key`.
The SSH request has a 30-second deadline. Failed SSH requests and malformed responses
stop startup. Stopping AgentD leaves its native sessions running; another invocation
reuses their state and registers a fresh AgentD instance.

HTTPS must already be enabled on Orion with a certificate trusted by the AgentD JVM.
The default `make run-server` configuration has HTTPS disabled. The launcher retains
certificate and hostname validation and does not change server TLS configuration.
For a private CA, configure the Maven JVM truststore before running the alias.

The equivalent Maven command is:

```sh
mvn -pl agentd -am -Pdev,run-agent process-classes
# Add -Dagentd.run.arguments='...' to pass launcher options.
```

The server administrative SSH command is:

```text
issue-launch-permit LABEL HTTPS_URI ABSOLUTE_STATE_DIRECTORY AGENT_VERSION
```

It requires application administrator authority, registers the label if needed, and
uses the same durable provisioning mechanism as server-controlled launches. Its
response is three newline-terminated lines: generation, launch UUID, and base64url
permit. An existing agent remains authoritative until its replacement authenticates;
issuing a permit alone does not disconnect it. A label registered with different
display metadata is rejected. The ordinary `AgentdMain` entry point continues to
accept provisioner-supplied parameters and the permit on standard input.

## Control runtime

`Agent.create` assembles the production control runtime around one process lock,
one session-discovery registry, one authenticated HTTP/2 control lifecycle,
and independent session journal pumps.
Startup acquires the process lock before discovery or network activity begins.
If startup fails, already-started services are closed in reverse order; normal
shutdown likewise stops reconnect and heartbeat work, closes the transport and
discovery monitor, and only then releases the process lock.

Each process launch creates a fresh in-memory instance UUID. `--agent-label` names
one unique logical agent on the server; the label remains stable across restarts.
The server/provisioner allocates startup authorization for either an unregistered
label or the exact current instance to replace. Issuance leaves that instance
active until a replacement successfully registers.

The initial connection authenticates with the launch permit. After the server
accepts it, AgentD keeps the returned reconnect token only in process memory and
uses it for bounded-backoff reconnects and periodic heartbeats. A rejected
initial credential fails startup. A rejected or revoked reconnect remains
offline and retries until AgentD is closed or replaced by a newly launched
generation. Reconnect keeps the same instance UUID, and a new process requires a
fresh startup permit. If the first WELCOME is lost after registration committed,
the provisioner requests a fresh authorized launch; the consumed permit stays unusable.

Session discovery continues while the server is unavailable. After reconnect,
the server requests a full session list and AgentD responds from the latest
completed discovery snapshot, so changes found offline are reconciled. Closing
or replacing AgentD does not terminate existing `session-host` processes or
alter their journals.

The control connection uses HTTPS with the configured server URI. Certificate
trust and hostname verification are performed by the production TLS client;
there is no plaintext or trust-all control path.

## Journal relay

After each authenticated connection, AgentD opens `/agent/session/{sessionId}`
streams for discovered sessions and resumes strictly after the server's durable
`SESSION_SYNC` cursor. Each pump reads at most 256 records and one maximum-record
byte budget per page, sends the original bytes in bounded chunks, and waits for
durable acknowledgement before reading the next page. Control and other
sessions keep separate transport capacity.

AgentD persists no replication state. Only server cursors authorize the
EventId-only native `ACK_JOURNAL` retention control; ambiguous ACK delivery is
repeated naturally on reconnect. An ahead-of-server host retention watermark
or corrupt complete journal record pauses that session. EventIds are ordered
but may skip numbers, so a cursor before the first available record resumes at
that record without reporting fabricated data loss. Reliable lost-record
continuity remains separate unfinished work.

The main Orion HTTPS listener serves both `/agent/control` and
`/agent/session/{sessionId}` on the same HTTP/2 connection. A successful control
handshake binds its context to that physical connection; an unauthenticated
connection cannot borrow another connection's label or session ownership.
Only one control stream is allowed per physical connection. Closing or replacing
the control connection resets its replication streams. Every replication open
and append also verifies the current durable label/instance registration.

## Local server acceptance

From the repository root on macOS or Linux, with the repository JDK, Maven,
Rust toolchain, and frontend Node dependencies available:

```sh
make run-test MODULE=net/http-core TEST='AgentSessionAcceptanceIT,AgentReplicationAcceptanceIT'
mvn verify -Pdev -T 4 -pl net/http-core -am \
  -Dit.test='JettyHTTPServerIT,AgentSessionAcceptanceIT,AgentReplicationAcceptanceIT,JettyHttp2LivePeerIT' \
  -Dfailsafe.failIfNoSpecifiedTests=false
cd net/frontend/ui
./target/node/node/node ./node_modules/vitest/vitest.mjs run src/lib/session-terminal.test.js
```

The Maven reactor builds the native host at
`session-host/target/cargo/debug/session-host` and installs the frontend Node
runtime. Acceptance launches real `AgentdMain` processes with isolated state,
a test truststore, and server-issued permits over standard input. It starts
native sessions through the existing server command API, verifies journal
completion, and drives `createOrionClient` and `followSessionTerminal` against
actual HTTPS history and live event responses. Recovery recreates server
services over the same durable directories and replaces AgentD while keeping
native sessions intact. Native tests fail if the local host binary is missing;
they are supported only on macOS and Linux. Test reports are in
`net/http-core/target/{surefire,failsafe}-reports`; process logs and terminal
results are retained under `net/http-core/target/acceptance`.

The fixture grants its explicit test administrator token access to client routes;
production deployments use the ordinary Orion authorization filter. AgentD uses
TLS certificate and hostname validation throughout. No running external Orion
server, SSH fleet, or packaged deployment is required. Remote administration,
Windows acceptance, journal continuity proofs, local-session deletion, semantic
projections, production object storage, and clustering remain deferred.

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
