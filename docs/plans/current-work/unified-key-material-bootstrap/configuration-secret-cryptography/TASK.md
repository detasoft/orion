# Add Configuration Secret Cryptography

Status: active
Owner: codex, session keymat-6f2a, started 2026-09-02 22:53 Europe/Amsterdam.
Depends on: ../typed-material-capabilities/TASK.md

Provide authenticated envelope encryption for secret payloads stored in the
versioned `orion.xml` document.

## Scope

- Store wrapping keys as purpose-scoped symmetric material without exposing
  them to callers.
- Define a versioned envelope containing key alias, wrapped data key, nonce,
  algorithm, encoding, and ciphertext.
- Bind organization, team, repository, secret id, kind, and schema version as
  authenticated context where applicable.
- Provide `seal` and `open` capabilities with short-lived plaintext handling.
- Reject plaintext secrets, moved ciphertext, tampering, unavailable keys, and
  unsupported envelope versions during configuration validation.
