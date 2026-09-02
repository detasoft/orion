# Add Transparent Remote Git Proxy Bootstrap

Status: active

Preserve direct `git+ssh`, `git+http`, `git+https`, and `git+file`
configuration locations by representing each remote as a persistent local
native Git proxy that can start before `orion.xml` and its colocated key store
are loaded.

- [ ] Implement the approved transparent remote Git proxy design.
  - Owner: codex, session rgp-9c3f1b, started 2026-09-02 14:49 Europe/Amsterdam.

## Scope

- Start a provisional transparent proxy from launch configuration and external
  bootstrap credentials before loading the remote configuration repository.
- Reconcile the provisional proxy with scoped persistent configuration after
  `orion.xml` and the key store have been loaded, adding it only when absent.
- Store runtime remote credentials through encrypted configuration and typed
  material capabilities while keeping bootstrap secrets external on every
  launch.
- Expose remote aliases separately from ordinary repositories and provide an
  Orion-internal proxy connection reference.
- Migrate retained integration coverage from JGit repository layout to native
  repositories and local Orion upstream servers.

Design: [Transparent remote Git proxy bootstrap](../../2026-09-02-transparent-remote-git-proxy-bootstrap-design.md)

## Child Tasks

- [ ] [Add the pre-bootstrap remote Git proxy runtime](bootstrap-proxy-runtime/TASK.md)
- [ ] [Persist scoped proxy aliases and encrypted credentials](proxy-config-and-secrets/TASK.md)
- [ ] [Expose remote aliases through the admin API and UI](proxy-admin-ui/TASK.md)
- [ ] [Migrate Git integration tests to native proxy behavior](native-integration-test-migration/TASK.md)
