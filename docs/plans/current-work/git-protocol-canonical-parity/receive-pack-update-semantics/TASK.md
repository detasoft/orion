# Align Receive-Pack Ref Update Semantics

Status: todo
Owner: codex, session 863d23b0, started 2026-09-01 19:12 Europe/Amsterdam.
Source: 2026-09-01 canonical parity audit against `builtin/receive-pack.c`.

Apply multi-ref commands independently by default and atomically only when the
server advertises and the client requests `atomic`. Report stale or invalid
commands per ref without suppressing otherwise valid non-atomic updates.

Use canonical refname validation and verify that every new ref target is
present and connected after quarantine ingestion before publication. Cover
mixed successful and stale commands, atomic rollback, invalid ref components,
missing target objects, and disconnected histories.
