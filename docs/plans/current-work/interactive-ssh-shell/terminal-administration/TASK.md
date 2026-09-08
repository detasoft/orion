# Fix Terminal Interaction and Add Configuration Administration

Status: active
Review: ../../../../../core/command/MODULE_REVIEW.md

- [ ] Fix terminal interaction and expose organization, Git proxy, and material administration.
  - Owner: codex, session terminal-admin-20260908-1639, started 2026-09-08 18:39 Europe/Amsterdam.

## Scope

- Make navigation, help, and completion cancellable without blocking terminal input.
- Correct redraw for long input, long prompts, and terminal resizing.
- Replace the full token when completing inside a word while preserving following arguments.
- Connect organization and Git proxy reads and provide authorized create, update, and delete commands.
- Import private keys and passwords through the terminal using the existing protected material and
  encrypted configuration mechanisms, keeping secrets out of echo, history, and audit output.
- Preserve Git transport behavior and existing authorization and persistence boundaries; cover the
  successful operations, invalid state, cancellation, persistence, and confidential input with tests.
