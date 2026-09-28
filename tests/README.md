# Test environment addresses

Default local development and integration-fixture endpoints:

| Service | Hostname | Host port | Protocol / purpose |
| --- | --- | --- | --- |
| Orion Admin API and packaged UI | `localhost` / `orion.test` | `8000` | HTTP |
| Frontend development server | `localhost` | `4173` | HTTP, Vite with automatic UI updates |
| Orion Git over SSH | `localhost` / `orion.test` | `8022` | SSH |
| Orion native Git | `localhost` / `orion.test` | `9419` | Git |
| Gitea and OIDC | `fixture.orion.test` | `8443` | HTTPS |
| Gitea Git over SSH | `fixture.orion.test` | `2223` | SSH |
| Test SSH server | `fixture.orion.test` | `2222` | SSH |
| S3 / SeaweedFS | `fixture.orion.test` | `8333` | HTTPS, S3 API |
| ACME / step-ca | `fixture.orion.test` | `9000` | HTTPS, `/acme/acme/directory` |
| ACME HTTP challenge proxy | `orion.test` | `8080` | HTTP, forwarded to container port `80` |
| Browser monitoring / noVNC | `localhost` | `6080` | HTTP, `/vnc.html?autoconnect=1` |
| Chromium DevTools / CDP | `localhost` | `9222` | HTTP/WebSocket, forwarded to container port `9223` |

Orion runs on the host when started with `make run-server`; its default HTTPS
listener is disabled. `make run-frontend` starts Vite separately. The external
services and Chromium share the `orion-external-services` container; their
published ports bind to host `127.0.0.1`.

For host access by the test names, `tests/external-services/fixture hosts-install`
adds `fixture.orion.test` and `orion.test` as aliases of `127.0.0.1` on macOS.
`tests/external-services/fixture hosts` prints the mapping without changing it.

Inside the container, `fixture.orion.test` reaches its own services and
`host.docker.internal` reaches the host. The fixture browser overrides
`orion.test` to reach the host's Orion server; other container processes resolve
it to the local ACME challenge proxy. Vite binds to host loopback by default, so
the container browser needs a reachable listener or forwarding for development UI
access. Hostname resolution and certificate trust are configured separately.

See [external-services setup](external-services/README.md) for startup, trust,
credentials, and service checks.
