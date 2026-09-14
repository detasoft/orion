# Orion Admin UI

A small Vue 3 administration console served by Orion. The UI uses same-origin
Admin API routes and never asks for a separate server URL. Open **Settings** to
provide an Admin API bearer token and, optionally, an SSH username. Tokens are
kept in session storage; usernames are kept in local storage.

The current Admin API exposes lifecycle and route information plus repository
creation. It does not expose repository, member, notification, or activity
lists. Repositories and activity created by the UI are therefore displayed only
for the current browser session.

## Session terminal

Open **Terminal** after connecting and enter a Session ID. The view replays
historical PTY output and follows new committed events through the same Admin
API stream. Recorded resizes apply in journal order. The terminal is currently
view only; interactive input and resize commands remain in task `03/05`.

An interrupted connection resumes after the last processed event, including
unknown events. Incomplete trailing records are discarded before reconnecting.
Event IDs retain unsigned 64-bit precision, and terminal output reaches xterm.js
as bytes. Closing the view cancels the stream; reopening starts replay from the
beginning. History stays visible after the session exits. PTY closure alone
does not stop following the session.

## Development

The current Vite toolchain requires Node.js 20.19+, 22.12+, or a newer release.

```sh
cd net/frontend/ui
npm install
npm run dev
```

The development server listens on `http://localhost:4173` and proxies `/api`
requests to `http://localhost:8000`. Change the proxy target in
`vite.config.js` when the local Orion HTTP listener uses another port.

## Checks

```sh
npm test
npm run build
```

The UI is also part of the Maven reactor. Maven installs its own pinned Node.js
toolchain, builds the UI, and packages the production assets in the `frontend-ui`
JAR. Frontend tests are temporarily excluded from Maven verification; run
`npm test` explicitly:

```sh
mvn verify -Pdev -T 4 -pl :frontend-ui -am
```

When Orion is running, the packaged console is available at `/` and `/ui`.
SSH and native Git clone buttons copy transport URLs. For HTTP(S), securely set
the `ORION_AUTH_HEADER` environment variable to `Authorization: Bearer <token>`
using the secret-prompt facilities of your shell. The UI then copies this
token-free command:

```sh
git --config-env=http.extraHeader=ORION_AUTH_HEADER clone "https://host/r/repository"
```

Unset `ORION_AUTH_HEADER` after the clone. The command is supported by current
Git versions in POSIX shells and PowerShell. It is intentionally not advertised
for `cmd.exe`, whose percent expansion can corrupt encoded repository paths.
