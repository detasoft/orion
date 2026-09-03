# Reuse One Repository Command Context

Status: todo
Plan: [Git wire architecture simplification](../../../2026-09-03-git-wire-architecture-simplification.md)

Resolve and bind invariant repository command state once after wire bootstrap
instead of reconstructing it for each operation in one Git command.

## Scope

- Introduce a command-scoped context containing the normalized repository path,
  resolved or created `NativeGitRepository`, access hook, and related helpers.
- Have the native TCP, SSH, and Smart HTTP bootstrap initiator create one
  context and pass it to the server session.
- Reuse that context for advertisement, negotiation, `ls-refs`, fetch, pack
  ingestion, and receive-pack publication within the same command.
- Resolve or create the repository once per command and use the same receive
  repository instance for ingestion and publication.
- Keep action-specific authorization checks, including per-want fetch checks
  and per-ref update checks, at the point where their inputs become known.
- Close command-owned resources once on success or failure.
- Do not cache contexts across sessions or bridge the separate Smart HTTP
  discovery and POST requests with shared mutable state.

## Completion Criteria

- Counting-provider tests show one repository resolution per command.
- Receive-pack tests show ingestion and publication use the same repository
  context.
- Existing access-denied and missing-repository behavior remains covered.
