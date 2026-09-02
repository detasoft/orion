# Build the Command Core and SSH Exec Adapter

Status: todo
Detailed plan: ../../../2026-09-02-interactive-ssh-shell.md

Introduce the Mina-independent command pipeline shared by SSH exec, the
interactive terminal, and future local or Make adapters.

## Scope

- Define command requests, authenticated context, hierarchical command nodes,
  parsed paths and arguments, cancellation, and structured result types.
- Parse absolute and relative resource paths, short actions, positional
  arguments, `name=value` parameters, and reserved `where` predicates.
- Resolve scoped resources by full ID, unambiguous prefix, or allowed unique
  name, with structured missing and ambiguous results.
- Apply existing Orion ACL checks to each resolved resource and filter list
  results independently of command visibility.
- Wrap dispatch in audit recording for user, SSH request/session, source,
  command path, redacted parameters, result, and duration.
- Route non-Git SSH exec through the dispatcher with stable plain output and
  exit codes; keep Git exec commands on the existing Git handler.
- Test parser boundaries, scoped resolution, ambiguity, denial, redaction,
  handler failure, cancellation, renderer independence, and Git compatibility.
