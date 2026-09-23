# Module Review: `git/git-sync`

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
