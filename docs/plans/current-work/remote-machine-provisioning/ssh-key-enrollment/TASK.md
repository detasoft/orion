# Enroll Orion SSH Keys

Status: todo
Depends on: ssh-runtime-bootstrap/TASK.md

Install a selected Orion public key without weakening remote account access.

## Scope

- Accept a bootstrap password for one attempt only and clear it afterward.
- Provide an idempotent `ssh-copy-id` equivalent that preserves unrelated keys
  and remote permissions.
- Verify key authentication before discarding the bootstrap credential.
- Support existing-key enrollment without requiring a password.
