# Add Querying and Automation Renderers

Status: todo
Detailed plan: ../../../2026-09-02-interactive-ssh-shell.md
Depends on: ../command-core-and-exec/TASK.md,
../read-only-domain-commands/TASK.md

Extend list commands with bounded filtering and stable formats suitable for
automation without changing domain handlers into presentation code.

## Scope

- Parse simple `where field=value` and `field!=value` predicates without a
  general expression language.
- Add column selection and pagination with deterministic ordering and explicit
  continuation metadata.
- Add terse and JSON renderers alongside interactive-table and stable plain
  renderers.
- Complete fields and enum values where command metadata can enumerate them.
- Keep ACL filtering ahead of query evaluation and prevent inaccessible values
  from leaking through errors, counts, pagination, or completion.
- Test quoting and invalid predicates, null and enum values, stable JSON/plain
  output, terminal widths, pagination boundaries, and no-PTY exit behavior.
