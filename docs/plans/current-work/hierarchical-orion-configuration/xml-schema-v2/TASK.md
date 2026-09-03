# Introduce the Versioned Orion XML Schema

Status: todo
Depends on: ../hierarchy-and-identifiers/TASK.md

- [ ] Introduce the versioned Orion XML schema.
  - Owner: codex, session xmlv2-2530f19c, started 2026-09-03 22:45 Europe/Amsterdam.

Replace the ACL-only XML root with a versioned Orion configuration document.

## Scope

- Add an `<orion schemaVersion="2">` root with system and organization
  sections and strict generated schema validation.
- Keep the current `<AccessControl>` document readable through an explicit v1
  translator without preserving it as the writable shape.
- Separate version-specific DTOs from the current immutable domain model.
- Produce deterministic XML suitable for meaningful Git diffs.
- Test legacy read, v2 round-trip, unknown fields, duplicates, and unsupported
  versions.
