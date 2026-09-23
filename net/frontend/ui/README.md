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
historical PTY output, then follows new committed events after the replay cursor.
Input stays disabled during history replay so old terminal queries cannot send
commands. Type or paste into the terminal after it starts following the session.
Use **Columns**, **Rows**, and **Resize terminal** to request a resize; dimensions
change when the corresponding journal event arrives.

An interrupted connection resumes after the last processed event, including
unknown events. Incomplete trailing records are discarded before reconnecting.
Event IDs retain unsigned 64-bit precision, and terminal output reaches xterm.js
as bytes. Closing the view cancels the stream; reopening starts replay from the
beginning. History stays visible after the session exits. PTY closure alone
does not stop following the session.

Input and resize use the existing server command service. Each command has a
fresh ID; requests are serialized and their existing server statuses are polled
until confirmed or failed. Up to 64 commands may await delivery or confirmation.
Failures pause input and report the affected command. Reopen the session to
continue; unsent queued input is discarded and failed or uncertain commands are
never automatically resubmitted.

The authenticated Admin endpoint is `/api/admin/sessions/{sessionId}/commands`.
POST JSON contains `commandId` and either `operation: "input"` with base64 `bytes`,
or `operation: "resize"` with integer `columns` and `rows` (1–65535). Bodies are
limited to 64 KiB. The server resolves session ownership from its registry.
GET with `?commandId=...` reads the existing command status. Responses expose
`commandId`, `sessionId`, decimal-string `operationSequence`, `phase`, `outcome`,
and `detail`; `SENT` alone does not establish execution success. Only a durable
journal result yields `CONFIRMED`.

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

The Maven frontend build uses Maven's JDK to run `FrontendBuild.java`. It holds an
OS lock on `.npm-build.lock` across `npm install` and `npm run build`, so concurrent
Maven builds in the same worktree wait instead of modifying `node_modules`
simultaneously. Installation reuses `node_modules` and updates dependencies as needed
without deleting the whole directory first. `--no-save` prevents the build from
rewriting `package.json` or `package-lock.json`.
Different worktrees build independently. The output directory
remains shared. The ignored lock file stays outside `target` and must not be deleted
while builds are running. Direct npm commands bypass this lock; do not run them, or Maven
`clean`, concurrently with a Maven build in the same worktree.

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
