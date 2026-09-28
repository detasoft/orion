# Test environment addresses

The first address column uses published ports on this Mac and needs no test
hostname aliases. The second column uses test names and requires the one-time
host setup below. HTTPS certificate requirements apply separately.

| Service | Address from this Mac | Address with test hostnames | Protocol / purpose |
| --- | --- | --- | --- |
| Orion Admin API and packaged UI | [http://localhost:8000](http://localhost:8000) | `http://orion.test:8000` | HTTP |
| Frontend development server | [http://localhost:4173](http://localhost:4173) | — | HTTP, Vite with automatic UI updates |
| Orion Git over SSH | `ssh://localhost:8022` | `ssh://orion.test:8022` | Use your Orion username and key |
| Orion native Git | `git://localhost:9419/<repository>` | `git://orion.test:9419/<repository>` | Git |
| Gitea | [https://localhost:8443](https://localhost:8443) | `https://fixture.orion.test:8443` | HTTPS; see certificate note below |
| Gitea Git over SSH | `ssh://git@localhost:2223/fixture/fixture.git` | `ssh://git@fixture.orion.test:2223/fixture/fixture.git` | Fixture repository |
| Test SSH server | `ssh://fixture@localhost:2222` | `ssh://fixture@fixture.orion.test:2222` | SSH |
| S3 / SeaweedFS | [https://localhost:8333](https://localhost:8333) | `https://fixture.orion.test:8333` | HTTPS, S3 API |
| ACME / step-ca | `https://localhost:9000/acme/acme/directory` | `https://fixture.orion.test:9000/acme/acme/directory` | ACME directory |
| ACME HTTP challenge proxy | [http://localhost:8080](http://localhost:8080) | `http://orion.test:8080` | Only `/.well-known/acme-challenge/` |
| Browser monitoring / noVNC | `http://localhost:6080/vnc.html?autoconnect=1` | — | HTTP |
| Chromium DevTools / CDP | [http://localhost:9222](http://localhost:9222) | — | HTTP/WebSocket; container port `9223` |

Orion runs on the host when started with `make run-server`; its default HTTPS
listener is disabled. `make run-frontend` starts Vite separately. The external
services and Chromium share the `orion-external-services` container; their
published ports bind to host `127.0.0.1`.

The browser integration scenario also starts Orion with HTTP on `8000`, SSH on
`8022`, and native Git on `9419`. All three transports are enabled; the scenario
stops its Orion server when it finishes. Other HTTP-only integration scenarios
keep Git native and SSH disabled.

The fixture's HTTPS certificates are issued for `fixture.orion.test`, so opening
an HTTPS `localhost` address in a browser produces a certificate warning. For
hostname-matching HTTPS access, run once from the repository root:

```sh
tests/external-services/fixture hosts-install
tests/external-services/fixture trust-mac
```

The first command adds `fixture.orion.test` and `orion.test` as aliases of
`127.0.0.1`; it requests `sudo` for `/etc/hosts`. The second trusts the fixture CA
in the login Keychain. Use `fixture.orion.test` for HTTPS because the certificate
is issued for that name. Other clients can use the CA file at
`tests/external-services/.state/step/certs/root_ca.crt`.
`tests/external-services/fixture hosts` prints the host mapping without changing it.

Gitea's OIDC issuer is `https://fixture.orion.test:8443/`; use that named address
for OIDC integration. To check Gitea from a CLI without changing `/etc/hosts`, use:

```sh
curl --cacert tests/external-services/.state/step/certs/root_ca.crt \
  --resolve fixture.orion.test:8443:127.0.0.1 https://fixture.orion.test:8443/
```

Gitea's fixture account is `fixture`; its password is stored locally in
`tests/external-services/.state/credentials.env` as `GITEA_PASSWORD`. Other service
credentials and SSH-key setup are described in the external-services guide below.

Inside the container, `fixture.orion.test` reaches its own services and
`host.docker.internal` reaches the host. The fixture browser overrides
`orion.test` to reach the host's Orion server; other container processes resolve
it to the local ACME challenge proxy. Vite binds to host loopback by default, so
the container browser needs a reachable listener or forwarding for development UI
access. Hostname resolution and certificate trust are configured separately.

See [external-services setup](external-services/README.md) for startup, trust,
credentials, and service checks.
