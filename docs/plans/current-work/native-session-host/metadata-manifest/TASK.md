# Reduce Metadata to a Session Manifest

Status: active
Design: ../../../2026-09-02-session-host-metadata-manifest-design.md

- [ ] Remove journal-derived state and per-event metadata rewrites.
  - Owner: codex, session metadata-7b4c, started 2026-09-02 22:07 Europe/Amsterdam.

## Scope

- Remove journal identity, active-segment, and timestamp-bound fields from
  metadata.
- Keep launch, lifecycle, control-endpoint, sandbox, and latest terminal-size
  information.
- Derive journal identity, segments, and bounds from writer state or journal
  files.
- Persist metadata only at session creation, process start, successful resize,
  and final lifecycle transitions.
- Update protocol documentation, compatibility fixtures, and focused tests.
