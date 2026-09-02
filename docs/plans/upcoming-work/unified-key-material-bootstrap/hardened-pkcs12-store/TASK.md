# Harden the PKCS12 Content Store

Status: todo
Depends on: ../contracts-and-bootstrap-order/TASK.md

Turn the current local PKCS12 implementation into a production bootstrap store.

## Scope

- Enforce safe owner-only permissions and reject unsafe existing files where
  the platform exposes permission checks.
- Preserve compare-and-swap updates while adding durable atomic publication,
  symlink protection, and explicit recovery behavior.
- Restrict plaintext and inline password references to explicit test or unsafe
  modes; support safe environment and protected-file bootstrap references.
- Add lifecycle handling for password material and redacted diagnostics.
- Cover create, reload, concurrent writers, crash boundaries, and corrupt input.
