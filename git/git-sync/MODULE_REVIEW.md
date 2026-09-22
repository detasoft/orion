# Module Review: `git/git-sync`

## 1. Fetched heads add a wrapper and copy around an existing immutable map

**Problem and evidence.** [GitFetchedHeads](src/main/java/pro/deta/orion/git/sync/GitFetchedHeads.java#L7)
contains only a heads map
and reconstructs/copies it.
[fetchHeads](src/main/java/pro/deta/orion/git/sync/SmartHttpGitRemoteGateway.java#L41) wraps the immutable
map already returned by `listHeads`; its sole production consumer,
[GitAttachment.attach](src/main/java/pro/deta/orion/git/sync/GitAttachment.java#L33), immediately unwraps it.
No additional fetched-result payload or outside consumer was found.

**Contract.** Return immutable branch heads only after object ingestion and tracking-ref publication.
Keep empty-remote behavior and valid, non-empty `refs/heads/` names.
The unfinished primary-upstream design requires those operations, not this extra representation.

**Minimal repair and validation.** Return the existing immutable map from `fetchHeads`, update the gateway,
attachment and test consumers, and remove the wrapper. Keep its name validation at the existing gateway
boundary rather than dropping it. Preserve empty/multiple-head
[SmartHttpGitRemoteGatewayTest](src/test/java/pro/deta/orion/git/sync/SmartHttpGitRemoteGatewayTest.java) cases,
[GitAttachmentTest](src/test/java/pro/deta/orion/git/sync/GitAttachmentTest.java) behavior and
invalid-input/result-immutability
coverage through the remaining API.

**Alternatives and consequences.** Adding another result abstraction is unnecessary. No persistence,
secret handling, remote CAS or tracking-publication change is required; the internal Java result type changes.

**Confidence and priority.** High for actual consumers. P3 small structural cleanup; do not confuse it with
removing the intentionally unfinished synchronization foundation.

## 2. Single and multi-head tracking publication duplicate one policy

**Problem and evidence.**
[publishTrackingRef](src/main/java/pro/deta/orion/git/sync/SmartHttpGitRemoteGateway.java#L133) and
[publishTrackingRefs](src/main/java/pro/deta/orion/git/sync/SmartHttpGitRemoteGateway.java#L174) independently
map branch names to
tracking refs, read expected IDs, publish and classify failure. Push uses the first, fetch the second.
`NativeGitRepository.updateRef` delegates to the same atomic `publishRefs` operation for one element;
no policy distinction was established.

**Contract.** Fetch publishes its tracking refs atomically; successful and already-current pushes update
their tracking ref with an expected-old check. Failures remain retryable local publication failures.

**Minimal repair and validation.** Call existing `publishTrackingRefs(repository, Map.of(refName, desiredId))`
from both push branches and remove the single-ref implementation. Add no API or state.
Retain [SmartHttpGitRemoteGatewayTest](src/test/java/pro/deta/orion/git/sync/SmartHttpGitRemoteGatewayTest.java)
coverage for multiple heads,
stale remote IDs, successful push, already-current retry and tracking-publication failure.

**Alternatives and consequences.** A separate strategy obscures equivalent behavior.
One-element atomic publication preserves the current storage path, CAS and notifications.

**Confidence and priority.** High from both helpers and the repository delegation.
P3 maintenance duplication; easy repair, with no present behavioral divergence demonstrated.
