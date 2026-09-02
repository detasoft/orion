# Reduce Metadata to a Session Manifest

Status: paused
Design: ../../../2026-09-02-session-host-metadata-manifest-design.md

- [ ] Remove journal-derived state and per-event metadata rewrites.
  - Owner: codex, session metadata-7b4c, paused 2026-09-02 22:23 Europe/Amsterdam;
    next: resume after the session-host task review.

## Scope

- Remove journal identity, active-segment, and timestamp-bound fields from
  metadata.
- Keep launch, process-identity, control-endpoint, sandbox, and latest terminal-size
  information.
- Derive journal identity, segments, and bounds from writer state or journal
  files.
- Persist metadata only at session creation, process identity publication, and
  successful resize.
- Update protocol documentation, compatibility fixtures, and focused tests.
