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
`.state/credentials.env`, starts the container, waits for Gitea, and creates its
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
automated Playwright scenario below configures and starts its own Orion.

The Playwright scenario is bound to Maven's `integration-test` phase in the
`external-services` profile. After starting the fixture, run:

```sh
mvn verify -Pdev,external-services -T 4 -pl tests/integration-test -am
```

To run only the ACME scenario, add `-Dit.test=PlaywrightAcmeIT`
`-Dtest=PlaywrightAcmeIT` `-Dsurefire.failIfNoSpecifiedTests=false`
`-Dfailsafe.failIfNoSpecifiedTests=false`.

The Java integration test starts Orion once, enrolls a test root key without
SSH, issues a test token, and runs Playwright in the container's visible Chromium.
Playwright opens Orion's **Key material** screen, presses **Issue ACME certificate**,
checks the new `orion.test` certificate, and fetches the saved chain through a
fresh API client. It also checks that an invalid bearer token cannot replace
the saved chain. The Java test reopens the material store after shutdown to
verify persistence. The profile supplies the fixture hostname to the test JVM
and the test trusts the fixture root CA. Orion listens on host port 8000 for
HTTP-01. The server stops when the test ends; the browser retains its last page.
The Playwright dependencies are installed from its lockfile in
`pre-integration-test`; Node.js 20 or newer and npm are required. No browser
download is needed because Playwright connects to the fixture Chromium through
the loopback-only DevTools port 9222.

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
