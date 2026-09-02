# Add Configuration Secret Cryptography

Status: todo
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
