# Test environment addresses

Use these addresses from an ordinary browser or client on this Mac, independently
of the container's test browser. HTTPS test names need the one-time host setup
below.

| Service | Address from this Mac | Protocol / purpose |
| --- | --- | --- |
| Orion Admin API and packaged UI | [http://localhost:8000](http://localhost:8000) | HTTP |
| Frontend development server | [http://localhost:4173](http://localhost:4173) | HTTP, Vite with automatic UI updates |
| Orion Git over SSH | `ssh://localhost:8022` | SSH; use your Orion username and key |
| Orion native Git | `git://localhost:9419/<repository>` | Git |
| Gitea and OIDC | [https://fixture.orion.test:8443](https://fixture.orion.test:8443) | HTTPS |
| Gitea Git over SSH | `ssh://git@fixture.orion.test:2223/fixture/fixture.git` | SSH; fixture repository |
| Test SSH server | `ssh://fixture@fixture.orion.test:2222` | SSH |
| S3 / SeaweedFS | [https://fixture.orion.test:8333](https://fixture.orion.test:8333) | HTTPS, S3 API |
| ACME / step-ca | `https://fixture.orion.test:9000/acme/acme/directory` | ACME directory |
| ACME HTTP challenge proxy | [http://localhost:8080](http://localhost:8080) | HTTP; only `/.well-known/acme-challenge/` |
| Browser monitoring / noVNC | `http://localhost:6080/vnc.html?autoconnect=1` | HTTP |
| Chromium DevTools / CDP | [http://localhost:9222](http://localhost:9222) | HTTP/WebSocket; container port `9223` |

Orion runs on the host when started with `make run-server`; its default HTTPS
listener is disabled. `make run-frontend` starts Vite separately. The external
services and Chromium share the `orion-external-services` container; their
published ports bind to host `127.0.0.1`.

For HTTPS access from a normal macOS browser, run once from the repository root:

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
