# Persist Scoped Proxy Aliases and Encrypted Credentials

Status: todo
Depends on: ../bootstrap-proxy-runtime/TASK.md,
../../unified-key-material-bootstrap/typed-material-capabilities/TASK.md,
../../unified-key-material-bootstrap/configuration-secret-cryptography/TASK.md

Adopt a provisional bootstrap proxy into persistent system, organization, or
project configuration without duplicating it on restart.

## Scope

- Model a remote repository location, stable scoped alias, internal proxy
  reference, and credential reference in versioned `orion.xml`.
- Match bootstrap and persisted proxies by normalized upstream identity and
  reject duplicate or conflicting definitions.
- Add the proxy and supplied credential exactly once when no persisted entry
  exists; leave an existing entry unchanged during later bootstrap runs.
- Store password and token values as authenticated encrypted envelopes and SSH
  private keys through purpose-scoped material aliases.
- Restrict credential references to the proxy scope or an ancestor and provide
  explicit credential rotation instead of implicit bootstrap replacement.
