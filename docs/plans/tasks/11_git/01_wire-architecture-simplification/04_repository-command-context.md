# Reuse One Repository Command Context

Status: todo

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

## Detailed implementation plan

### Task 5: Bind one repository context per command

**Files:**

- Create: `net/git-transport/src/main/java/pro/deta/orion/transport/git/GitRepositoryCommand.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/DefaultGitNativeRepositoryService.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/GitBlockingWireSession.java`
- Modify: native TCP, SSH, and Smart HTTP bootstrap initiators
- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/DefaultGitNativeRepositoryServiceTest.java`
- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/GitBlockingWireSessionTest.java`

1. Add a counting `NativeGitRepositoryProvider` test proving that one command
   currently resolves the same repository more than once across advertisement,
   negotiation, and fetch or receive completion.
2. Add receive-pack coverage proving that ingestion and publication must share
   one resolved repository instance.
3. Run the focused transport tests and record RED against the one-resolution
   expectation.
4. Introduce a concrete command-scoped object holding `InitialRequestData`, the
   normalized path, one resolved or created `NativeGitRepository`, and the
   access hook. Prefer a concrete object over a second generic service port.
5. Move repository operations behind methods on that context and remove
   repeated `InitialRequestData` and access-hook parameters.
6. Keep `beforeFetch` checks per requested want and `beforeUpdate` checks per
   ref; only invariant lookup and initial read/receive authorization move to
   context creation.
7. At each bootstrap entrypoint, open one context after parsing initial request
   data, pass it into `GitBlockingWireSession`, and close command-owned resources
   once. Treat Smart HTTP discovery and POST as separate commands.
8. Delete the single-implementation repository service interface if no second
   production implementation remains after the migration.
9. Rerun the focused native TCP, SSH, HTTP, and repository tests and expect
   GREEN with one provider resolution per command.
10. Commit the command-context change and tests.
