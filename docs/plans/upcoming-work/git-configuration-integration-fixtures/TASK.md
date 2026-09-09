# Repair Git Configuration Integration Fixtures

Status: todo

Observed during `mvn verify -Pdev -T 4` for HTTP/2 control transport
(`8e00fb1c`): `LocationConfigurationProviderIT` fails in
`readsConfigurationFromGitHttpRepository` and
`readsConfigurationFromGitSshRepository` with
`Configuration location not found or unsupported: git+http://...` and
`Configuration location not found or unsupported: git+ssh://...`.
These tests use separate `GitHttpTestServer` and `GitSshTestServer` fixtures.

## Scope

- Determine whether the failures come from stale fixture setup, resolver
  composition, or incomplete migration to the current remote configuration path.
- Align both local fixtures and their configuration reads with the supported
  production path; preserve meaningful HTTP/SSH configuration coverage.
- Coordinate with `current-work/remote-git-proxy-bootstrap/native-integration-test-migration`.
  Do not restore a superseded internal API solely to satisfy old tests.

## Acceptance

- Both integration scenarios load and assert the seeded configuration through
  the current runtime contract, using deterministic local upstreams.
- Verify the affected integration tests and rerun `mvn verify -Pdev -T 4`;
  report remaining unrelated failures separately.
