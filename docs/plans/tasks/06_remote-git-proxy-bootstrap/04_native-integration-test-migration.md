# Verify Native Remote Bootstrap and Proxy Adoption

Status: todo
Depends on: completed bootstrap runtime boundaries and native transport acceptance (`79dd66b4`),
completed persistent proxy adoption and credential activation (`a79b1f2d`, `8750c1ee`),
[06/05](05_git-configuration-integration-fixtures.md).

## Requirements and design

Verify remote bootstrap against a second local Orion runtime exposing native
HTTP and SSH Git. Share deterministic fixture support from 06/05. Tests must
assert public APIs, snapshots, and observable refs rather than JGit storage
layout, a `config` file, an assumed `master` branch, or `jgit-runtime` lifecycle.
Proxy UI and background GitHub mirroring are not prerequisites for these tests.

## Implementation plan

1. Seed configuration and encrypted material through native APIs and launch
   the target using process configuration with independent source descriptors.
2. Cover first adoption, unchanged configuration on restart, one alias for two
   paths, independent upstreams, and local inputs without a proxy.
3. Verify HTTP/SSH reads and upstream-CAS writes, including remote movement,
   denied credentials, missing refs/paths, wrong material password, and failure
   before public transport activation.
4. Verify authorized stable aliases and rejection of internal cache access;
   retain compressed Smart HTTP interoperability coverage.
5. Remove migrated fixture/layout assumptions only after equivalent observable
   coverage exists through the current runtime path.

## Acceptance

Remote configuration and material start correctly over both transports; bad
inputs do not activate stale configuration. Adoption survives restart without
secret exposure or duplicate entries. Focused integration scenarios and the
full `mvn verify -Pdev -T 4` pass, or unrelated environment failures are reported
explicitly. Docker-backed S3 coverage remains independent.
