# Migrate Server Identity and JWT Signing

Status: todo
Depends on: ../typed-material-capabilities/TASK.md

Make the unified material store the only owner of Orion server identity and JWT
signing keys.

## Scope

- Import the legacy `server-identity` private key without silently replacing an
  existing identity.
- Replace `ServerIdentityKeyService` file ownership with signing and
  verification capabilities backed by configured aliases.
- Preserve old verification keys during rotation and restart.
- Define conflict, missing-key, wrong-purpose, and migration recovery behavior.
- Remove the legacy primary storage path after compatibility migration is safe.
