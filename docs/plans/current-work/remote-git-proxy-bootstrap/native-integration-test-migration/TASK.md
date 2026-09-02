# Migrate Git Integration Tests to Native Proxy Behavior

Status: todo
Depends on: ../bootstrap-proxy-runtime/TASK.md,
../proxy-config-and-secrets/TASK.md,
../proxy-admin-ui/TASK.md

Retain the remote configuration and ACL contracts while removing JGit-specific
repository fixtures and assertions from runtime integration tests.

## Scope

- Use a second local Orion runtime as HTTP and SSH upstream instead of reading
  or seeding JGit bare repository layout.
- Verify direct remote launch, provisional proxy handoff, first-run persistence,
  restart deduplication, reads, writes, and upstream compare-and-set conflicts.
- Assert native repository behavior through public APIs and snapshots rather
  than `config`, `master`, or filesystem layout assumptions.
- Replace stale `jgit-runtime` lifecycle expectations with current native
  transport state while preserving SSH and HTTP parity.
- Cover invalid credentials, missing upstream state, encrypted credential
  reload, and local locations that must not create proxies.
