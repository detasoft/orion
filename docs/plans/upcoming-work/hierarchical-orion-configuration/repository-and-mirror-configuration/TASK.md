# Add Repository and Mirror Configuration

Status: todo
Depends on: ../xml-schema-v2/TASK.md,
../../unified-key-material-bootstrap/configuration-secret-cryptography/TASK.md

Represent repository desired state, including external Git synchronization,
inside the owning team in `orion.xml`.

## Scope

- Add repository identity, display metadata, default branch, policies, and
  provider-neutral mirror definitions under `org/team/repo`.
- Define explicit direction, ref mappings, triggers, and force, delete, and tag
  conflict policies.
- Store remote credentials as organization or repository-scoped encrypted
  values or references without plaintext readback.
- Keep queue, retry, lease, conflict observation, and last-sync state separate.
- Validate remote identity, unsafe refspecs, secret scope, and duplicate mirrors.
