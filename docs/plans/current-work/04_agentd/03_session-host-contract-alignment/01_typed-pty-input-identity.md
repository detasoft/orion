# Correct the Typed PTY Input Identity

Status: todo
Parent: TASK.md
Finding: ../../../../../agent-protocol/MODULE_REVIEW.md
Current contract: ../../../2026-09-03-native-control-journal-idempotency-design.md

- [ ] Correct the typed PTY input identity.
  - Owner: codex, session agent-protocol-pty-input-id-6b7c, started 2026-09-09 19:02 Europe/Amsterdam.

Align the shared Java journal payload model with the native producer without
changing the version-1 journal bytes.

## Scope

- Name the first `PTY_INPUT` payload field `ptyInputId` in the typed Java model
  and in both shared and native protocol documentation.
- Replace the typed Java payload's `CommandId` representation with the existing
  permissive text representation of the input identity; add no new wrapper.
- Update every in-repository typed consumer, fixture construction, and test.
- Preserve the encoded text bytes, byte-for-byte version-1 fixtures, opaque
  event forwarding, and the native operation sequence used for replay control.

## Acceptance

- Tests use distinct server command and input identities and cannot mistake one
  for the other.
- Existing version-1 fixture bytes decode and re-encode unchanged.
- No new identity, deduplication, journal-confirmation, or wire-version concept
  is introduced.
