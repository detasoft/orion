# Module Review: `net/git-transport`

## 5. Wanted refs are resolved separately for authorization and planning

**Problem and evidence.**
[checkFetchAccess](src/main/java/pro/deta/orion/transport/git/NativeGitRepositoryContext.java#L58)
takes snapshot A and resolves `want-ref` names/HEAD before checking reachable-branch access.
[prepareNegotiation](../../git/git-parser/src/main/java/pro/deta/orion/git/parser/v2/command/FetchCommand.java#L86)
then takes snapshot B and calls
[resolveWantedRefs](../../git/git-parser/src/main/java/pro/deta/orion/git/parser/v2/fetch/NegotiationContext.java#L183)
to resolve the same names again.
If a tag or HEAD moves from allowed commit A to denied commit B between these steps, authorization checks A
while the fetch plan can serve B. The duplicated resolution has different owners and snapshots.

**Contract.** Authorize the exact requested objects used for transfer, including named requests.
Preserve symbolic/detached HEAD, unknown-ref rejection, legacy advertised/reachable-want restrictions,
per-object branch policy and protocol-specific negotiation.

**Minimal repair and validation.** Resolve names once in the existing NegotiationContext using one
request-scoped RefsSnapshot; authorize those resolved IDs against that snapshot and retain them for planning.
Adapt the existing repository-context hook while keeping branch policy at the transport boundary.
No persistent cache or second request model is needed.

Use a deterministic access-hook regression: allowed main at A, denied branch at B, requested tag/HEAD
initially at A; change that name to B immediately after authorizing A. The plan must keep A or reject B after
reauthorization. Preserve
[branch-access tests](src/test/java/pro/deta/orion/transport/git/DefaultGitNativeRepositoryServiceTest.java#L243)
and
[wanted-ref wire-session tests](src/test/java/pro/deta/orion/transport/git/GitBlockingWireSessionTest.java#L246).

**Alternatives and consequences.** Sharing only a name-parsing helper leaves the snapshot race intact.
A broad repository lock is unnecessary when immutable IDs and the existing context can bind the request.
This changes an internal hook contract, not the Git wire protocol.

**Confidence and priority.** High from the authorization-to-plan call chain; no runtime reproduction run.
P1 authorization inconsistency; prioritize before the maintenance-only cleanups.
