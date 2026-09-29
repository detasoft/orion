# External integration services

One image runs the external systems Orion needs to contact during integration testing:
OpenSSH, Gitea (Git over SSH/HTTPS and OIDC), SeaweedFS (S3), step-ca (ACME),
and Chromium with noVNC. The image is built from the pinned Gitea release plus
Alpine packages and pinned step-ca/SeaweedFS binaries. All services share one
container and a persistent, ignored `.state` directory.

## Start a test round

From this directory:

```sh
./fixture up
./fixture check
```

`up` builds the image when absent, creates random credentials in
`.state/credentials.env`, starts the container, waits for Gitea and Chromium, and creates its
fixture user. `check` exercises the ACME directory, Git HTTPS push/SSH fetch,
SSH password/key login and rejection, S3 access, and browser CA trust.
Run `./fixture down` after the round. Data and host keys remain in `.state`.
Rebuild with `./fixture build` after changing the image. Docker is the default;
use `CONTAINER_ENGINE=podman ./fixture up` and the same prefix for other commands.
On macOS Podman starts/stops its VM with the fixture. Podman requires an
initialized machine (`podman machine init`) once.

The first build downloads step-ca 0.30.2 and SeaweedFS 4.47 into ignored `.cache`.
Both arm64 and amd64 archives are checked against pinned SHA-256 values. `docker build`
directly works after those archives are cached by `./fixture build`.

| Service | Address and credential |
| --- | --- |
| Normal SSH | `ssh -p 2222 fixture@fixture.orion.test`; `SSH_PASSWORD` |
| Gitea SSH | `ssh://git@fixture.orion.test:2223/fixture/fixture.git`; upload a user key in Gitea |
| Gitea HTTPS/OIDC | `https://fixture.orion.test:8443`; user `fixture`, `GITEA_PASSWORD` |
| S3 | `https://fixture.orion.test:8333`; `S3_ACCESS_KEY`, `S3_SECRET_KEY` |
| ACME directory | `https://fixture.orion.test:9000/acme/acme/directory` |
| Browser in container | `http://localhost:6080/vnc.html?autoconnect=1` |

The credentials are local test secrets. Ports bind to `127.0.0.1` on the host.
For macOS clients, `./fixture hosts-install` adds this line to `/etc/hosts`
after a `sudo` prompt; `./fixture hosts` prints it without changing the host:

```text
127.0.0.1 fixture.orion.test orion.test # orion-external-services
```

On Linux, add the same mapping using the local host configuration. The fixture
container has its own mappings: `fixture.orion.test` reaches its services and
`orion.test` reaches its challenge proxy. Name resolution and certificate trust
are separate; clients must also trust the CA root.

The CA root is `.state/step/certs/root_ca.crt`, or `./fixture ca`. The OpenSSH
host public key is `.state/ssh/ssh_host_ed25519_key.pub`; pin it in a client's
`known_hosts` for host-key verification. Recreating `.state` rotates the CA and
SSH host key and invalidates existing trust and pins.

## Orion connection scenarios

For password-to-key SSH enrollment, use `fixture@fixture.orion.test:2222` and
`SSH_PASSWORD`, install Orion's public key into the fixture user's
`authorized_keys`, then open a new key-authenticated connection. The server
still accepts the password after the client switches to a key; disabling it
requires changing server policy. `./fixture check` verifies both accepted and
rejected credentials.

Create or inspect Git repositories in Gitea. `./fixture check` creates the
private `fixture/fixture` repository, pushes a temporary branch over HTTPS,
reads it over SSH, and deletes the branch. For S3, use path-style addressing
and the credentials above; the check creates bucket `orion-fixture`.

For OIDC, register a confidential Gitea OAuth2 application at
`https://fixture.orion.test:8443/user/settings/applications` with Orion's exact redirect
URI, for example `https://orion.test:9443/api/auth/oidc/callback`. Use issuer
`https://fixture.orion.test:8443/`, Authorization Code flow, scopes `openid email profile`,
and PKCE S256. Orion requires RS256 ID tokens with verified email. Set Orion's
public URL to the same `https://orion.test:9443` origin and trust the fixture CA
in Orion's Java truststore. The fixture check verifies OIDC discovery; completing
the interactive authorization requires configuring Orion's application.

For a manually started Orion ACME HTTP-01 flow, configure its directory URL to the value in the
table, its requested domain to `orion.test`, and its HTTP challenge listener on
host port 8000. Set `transport.defaultAddress: 0.0.0.0` in the test Orion
bootstrap configuration so the container can reach that listener. In the
versioned `orion.xml`, keep the HTTPS listener disabled until issuance, enable
`system.https.acme`, set `directoryUrl` to the table's ACME URL, set its sole
domain to `orion.test`, and keep the account, identity, and issuer material
references configured. **Orion requests and installs its own certificate.** During
validation, step-ca resolves `orion.test` to the fixture's Nginx on port 80;
Nginx forwards `/.well-known/acme-challenge/*` to
`host.docker.internal:8000`, where Orion answers. The host's port 8080 maps to
the fixture's port 80 for inspecting this route. After Orion has requested the
certificate and started HTTPS on port 9443, run `./fixture check-orion` to
verify its hostname and CA chain. The ordinary `check` verifies that the ACME
directory is available; it does not request a certificate for Orion. The
automated integration round configures and starts its own Orion.

Browser integration tests run in Maven's `integration-test` phase in the
`external-services` profile. From the repository root, run:

```sh
make integration-test
```

This prints the noVNC browser URL immediately and runs Maven. The
`pre-integration-test` phase starts the fixture if needed and installs Playwright
dependencies. A running fixture is reused. Its `.state` data and host
keys persist. The fixture remains running afterward; stop it with
`tests/external-services/fixture down`.

The integration round starts an isolated Orion instance, configures test access
and CA trust, and runs Playwright in the container's visible Chromium. Orion
listens on host port 8000 for HTTP-01 and stops when the round ends. Each test
uses a clean browser context; its pages close after success or failure. Existing
manual browser pages stay open.
The Playwright dependencies are installed from its lockfile in
`pre-integration-test`; Node.js 20 or newer, npm, and Git are required. No browser
download is needed because Playwright connects to the fixture Chromium through
the loopback-only DevTools port 9222.

## Test an already running Orion

Start the environment and server explicitly, then run the browser client against
the URL you select. Currently services, Chromium and noVNC share one container,
so the first command starts both the services and the browser:

```sh
tests/external-services/fixture up
npm --prefix tests/integration-test/playwright ci

# In another terminal; see the repository README for first-time initialization.
make run-server

# Fast: test plain HTTP without ACME or JVM CA configuration.
make browser-test URL=http://localhost:8000 TEST='local repository'

# Observe the same scenarios through noVNC, with a one-second action delay.
make browser-test URL=http://localhost:8000 OBSERVE=1 TEST='local repository'

# Run all ordinary scenarios, including the HTTPS Git proxy (see JVM CA trust below).
make browser-test URL=http://localhost:8000
```

`fixture up` waits for Gitea and the browser's DevTools endpoint to become ready.
The goals connect to the running server and browser; they do not start, restart,
or stop either one. `URL` is required and printed before the scenarios start.
The HTTP client uses that URL. For host loopback URLs (`localhost`, `127.0.0.1`,
or `::1`), the container browser uses the existing `orion.test` host mapping with
the same port and path. Both addresses are printed. Other hostnames stay unchanged
and must be reachable from both the host and container. The Orion listener must
be reachable from the container, normally by binding to `0.0.0.0`.

If `ORION_TOKEN` is set, tests use it. Otherwise they obtain a short-lived root
token using the existing `make issue-token-raw` SSH command and the enrolled key
selected by your SSH configuration or agent. The SSH host defaults to the host
in `URL`; override `ORION_SSH_HOST`, `ORION_SSH_PORT` (default `8022`), or
`ORION_SSH_OPTIONS` when needed. The one-time key enrollment remains the ordinary
`make enroll-admin-key` operation. Tokens and entered credentials are not printed.

The local-repository scenario only needs Orion and Chromium. The Git-proxy
scenario also needs Gitea, its fixture credentials and CA, and an Orion JVM that
trusts that CA. Each run creates uniquely named Orion repositories and proxy
aliases, which remain in the selected server's persistent configuration. The
temporary upstream Gitea repository is removed after its scenario.

Observed tests bring their page forward, show the scenario name, and mark clicks.
Unattended tests use the same assertions without presentation delays. Results are
shown in the terminal; the HTML report is in
`tests/integration-test/target/playwright-report/`. Failed test traces are in
`tests/integration-test/target/playwright/`. These local artifacts can contain
test credentials and should remain private.

ACME issuance is a separate, explicit invocation:

```sh
make browser-acme-test URL=http://localhost:8000 OBSERVE=1
```

The current ACME scenario tests certificate issuance through the UI after ACME
is configured, as described above; it does not yet configure ACME through a UI
form. It expects the fixture's `orion.test` domain and an identity without an
issued certificate. The complete Maven integration round prepares that state
and invokes both browser goals. `OBSERVE=1` also applies to that round.

## Lifetime and reset boundaries

noVNC shows the container's desktop whenever the container runs, including while
no tests or Orion server are running. Supervisor keeps Chromium and noVNC alive.
An open manual page can show an old response after its server has stopped.

Each scenario starts with clean cookies and storage in its own browser context;
closing that context removes its pages and browser state. This does not reset
Orion's server-side data, Gitea, S3 or the CA. `fixture down` stops the container
and preserves `.state`; a subsequent `fixture up` starts it again with a new
Chromium profile. Container recreation also preserves the bind-mounted `.state`.
Replacing `.state` is a full data reset that rotates credentials, the CA and SSH
host keys; existing trust and host-key pins then need updating.

Run one observed scenario sequence at a time on this desktop. The current fixture
name and ports are shared across worktrees; stopping or rebuilding that container
also affects other users of it.

Chromium's CA trust is installed into its NSS database before browser startup.
Orion's JVM trust is separate: the JVM must also trust the fixture CA for Gitea
HTTPS and ACME. Browser trust does not configure JVM trust, and browser contexts
keep certificate validation enabled. When the browser moves to its own container,
pass the service fixture's root CA to that container before launching Chromium.

For a local JVM started with a dedicated test truststore, one way to add the
root is:

```sh
keytool -importcert -noprompt -alias orion-external-ca \
  -file .state/step/certs/root_ca.crt -keystore .state/orion-truststore.p12 \
  -storetype PKCS12 -storepass changeit
```

Start that JVM with
`-Djavax.net.ssl.trustStore=<absolute path to .state/orion-truststore.p12>` and
`-Djavax.net.ssl.trustStorePassword=changeit`. Use this test store only for a
local Orion process; it contains only the fixture root, so it does not retain
the JVM's usual public roots.

## Browsers and trust

The container's Chromium uses a 1440×900 Xvfb screen. Its NSS database trusts
the fixture root CA; certificate validation stays enabled. Open
`http://localhost:6080/vnc.html?autoconnect=1` to watch or drive it. For a
manually running Orion configured for this CA, open `http://orion.test:8000/`,
connect with an application-admin bearer token under **Settings**, then open
**Key material** to inspect public keys and certificates or issue a new ACME
certificate. The interface never returns private or symmetric key bytes.

On macOS with Google Chrome installed, run `./fixture trust-mac` once to add the
root to your **login Keychain** as a trusted root. This affects other applications using that
Keychain, so run it only for the test CA you inspected with `./fixture ca`.
Then `./fixture chrome` opens Google Chrome in a separate profile with a
1440×900 window. Its host resolver maps both test names to localhost. The
profile remains in `.state/chrome-mac`. Changing the
CA requires trusting the new root again and removing the old trust entry from
Keychain Access.

The fixture is a local test service. Do not expose its ports publicly or reuse
its generated CA and credentials for production systems.
