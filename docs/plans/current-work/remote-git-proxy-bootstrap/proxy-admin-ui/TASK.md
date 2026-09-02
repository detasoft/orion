# Expose Remote Aliases Through the Admin API and UI

Status: todo
Depends on: ../proxy-config-and-secrets/TASK.md

Make proxy-backed repositories identifiable without publishing their internal
native repositories as ordinary user repositories.

## Scope

- Return remote aliases separately from local repositories, including scope,
  alias, sanitized upstream, transport, internal reference, and sync status.
- Never return credential values, encrypted payloads, secret-bearing query
  parameters, or material details through read APIs.
- Add a dedicated Remote aliases UI section with copyable internal references
  and clear unavailable, authentication-failed, and conflict states.
- Support credential replacement and retry commands through explicit audited
  operations.
