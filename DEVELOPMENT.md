# Development environment

Run the commands below from the repository root on macOS or Linux.

## Processes and ownership

| Process | Start | Purpose |
| --- | --- | --- |
| Orion server | `make run server` | Backend, packaged UI, HTTP on `8000`, SSH on `8022` by default |
| AgentD | `make run agent` | Local agent connected to Orion; requires a running server and enrolled admin key |
| Vite | `npm --prefix net/frontend/ui run dev` | UI development on `4173`, with hot updates and `/api` proxied to `8000` |
| Server + Vite | `make run dev` | Start both in one terminal; exiting either stops the other |

These commands run in the foreground. `make run dev` does not start AgentD: start the
agent in another terminal after Orion is ready. Ctrl-C stops the processes owned
by the command, including their descendants. A process that does not stop within
ten seconds is killed.

Maven compiles and runs the Java entry points. Node.js coordinates development
processes and runs Vite; no Python, RAM disk, or global Maven lock is used in this
startup path. The process launcher prevents duplicate services within one
checkout, but does not serialize Maven builds. Avoid concurrent builds that
rewrite the same module's `target` directory.

## Prerequisites

- JDK 21 or newer compatible with the project, Maven, Git, OpenSSH, and Make.
- Node.js and npm on `PATH`. The Maven frontend build pins Node.js `22.22.2`;
  use the same version for local UI development.
- Rust toolchain `1.97.0` for the native session host. `make rust-install`
  installs the pinned toolchain, installing rustup first if necessary.
- A desktop browser. Automatic opening uses `open` on macOS and `xdg-open` on
  Linux; `BROWSER=none` disables opening during `make run dev`.

Python remains a tool for optional maintenance scripts and launcher tests; it is
not required to start the server, agent, Vite, or Maven tests. Docker is needed
for the external integration-test fixture, not for the basic local environment.

Install frontend dependencies once and after the lockfile changes:

```sh
npm --prefix net/frontend/ui ci
```

If a clean Maven installation cannot resolve the repository's Rust Maven plugin,
install its tagged version locally:

```sh
make rust-maven-plugin-install
```

## First start

Set the password for the protected key-material store in each server terminal:

```sh
export ORION_KEY_MATERIAL_PASSWORD='choose-a-local-development-password'
make init-server
```

`init-server` starts the server with `--create-if-missing`. Use it to initialize a
new installation, not to replace missing keys in an existing installation. Keep
the password for subsequent starts and follow the
[key-material backup guide](docs/key-material-backup-and-restore.md).

While the server is running, enroll the admin SSH key from another terminal:

```sh
make enroll-admin-key
```

The command uses the SSH client's key configuration and prompts for the initial
Orion root password. Enrollment is needed for the local agent launcher and for
automatic development UI login. Stop the initial server with Ctrl-C before
switching to `make run dev`.

## Daily development

```sh
export ORION_KEY_MATERIAL_PASSWORD='your-existing-store-password'
make run dev
```

Once Orion is ready, start AgentD in a separate terminal:

```sh
make run agent
```

The agent requests a launch permit over SSH and connects to
`http://localhost:8000`. The default `AGENT_ARGS=--allow-unsecure` permits local
HTTP; HTTPS still validates certificates. Use `AGENT_ARGS='--help'` to inspect
the available server, state-directory, label, and SSH options. Include
`--allow-unsecure` explicitly when overriding arguments for an HTTP server.

Vite opens a browser with an authenticated development session under `make run dev`.
The opener waits up to two minutes for Orion and obtains a token through the
enrolled SSH key. For an already running Vite instance:

```sh
make open-ui
# If Vite selected another port:
make open-ui URL=http://localhost:4174
```

Starting Vite directly with npm does not request automatic Orion login; use
`make open-ui` when needed. Vite can choose another port when `4173` is occupied;
use the URL printed at startup.

`ORION_ARGS` supplies server arguments, for example:

```sh
make run server ORION_ARGS=--reset-root-pass
```

`make run session COMMAND=/bin/sh` is a separate local terminal-session
diagnostic. It builds the Rust session host and runs AgentD's `terminal start`
command; it is not the connected agent started by `make run agent`.

## Status, stopping, and PID records

```sh
make status
make status SERVICE=server
make stop SERVICE=agent
make stop
```

`SERVICE` accepts `server`, `agent`, or `vite`. Without it, commands inspect or
stop all three. Stopping the server or Vite owned by `make run dev` also ends its paired
process; the separately started AgentD remains independent.

The launcher writes `target/dev-processes/server.pid`, `agent.pid`, and
`vite.pid`. Each JSON record contains the supervisor and child PIDs plus their
process start times. Status checks the recorded process identity, rather than
treating file existence as proof that a process is alive. A running Maven
process may still be compiling: `running` does not mean the HTTP server is ready
or that AgentD has connected. Check startup output and the UI for readiness.

Normal termination removes the record. After a crash, `make status` may report
a stale record. Run `make stop SERVICE=<name>` to clean it up before restarting;
the launcher refuses to overwrite an existing record. Stop can also recover a
still-running child after its supervisor was killed. It does not signal a PID
whose start time differs from the stored identity.

Do not delete active PID records or run `clean` against their directory. The
records cover these development launch commands only; they do not discover
processes started by direct `mvn` or `java` invocations. `ORION_DEV_RUN_DIR` can
override the record directory; use the same value for start, status, and stop.

The packaged distribution's `start/stop/status` commands have their own existing
service-manager PID file. AgentD's `agentd.lock` protects its state directory.
Neither is replaced by the checkout's development-process records.

## Files and configuration

| Location | Contents |
| --- | --- |
| `orion_root/` | Default local server state, including protected key material |
| `orion_root/agentd-local/` | Default connected AgentD state and its process lock |
| `orion_root/logs/` | Default scoped server logs |
| `target/dev-processes/` | Development launcher PID records |
| Module `target/` directories | Maven and native build outputs |
| `net/frontend/ui/node_modules/` | UI development dependencies |

Keep runtime data and secrets out of Git. See the root [README](README.md) for
server configuration and [frontend guide](net/frontend/ui/README.md) for UI details.

## Tests

```sh
make test
make test MODULE=tests/test-support TEST=SessionHostLinuxMakefileTest
make test MODULE=core/bootstrap TEST='AppTest#*' LOG=/tmp/orion-focused.log
make session-host-test
npm --prefix net/frontend/ui test
```

`MODULE` and `TEST` must be supplied together. `LOG` is optional for full and
focused Maven tests. Make uses `package -Pdev -T 4 -q`, includes reactor
dependencies for focused tests, and uses ordinary system temporary storage.
Build caching can reuse successful results. To bypass it:

```sh
make test MAVEN='mvn -Dmaven.build.cache.enabled=false'
```

For integration services and browser tests, follow
[tests/README.md](tests/README.md) and the
[external-services guide](tests/external-services/README.md). `make help` lists
all supported goals. `make browser-test URL=http://localhost:8000 OBSERVE=1`
opens the fixture's noVNC view and tests an already running Orion instance.
