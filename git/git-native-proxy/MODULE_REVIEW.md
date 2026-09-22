# Module Review: `git/git-native-proxy`

## 3. Provisional location metadata is stored in two registries

**Problem and evidence.**
[Provider registries](src/main/java/pro/deta/orion/git/proxy/ProxyAwareNativeGitRepositoryProvider.java#L42)
store both `provisionalBindings` and `provisionalLocations`, while each
[runtime](src/main/java/pro/deta/orion/git/proxy/BootstrapGitRuntimeProxy.java#L18) already owns its immutable
location.
[prepareProvisional](src/main/java/pro/deta/orion/git/proxy/ProxyAwareNativeGitRepositoryProvider.java#L141)
inserts the same metadata
twice and coordinates paired rollback at lines 182–185. Resolution failure, activation and adoption also
remove, clear or query both registries. Every bootstrap source must maintain their agreement.

**Contract.** Preserve compatible source sharing, conflicting authentication rejection, failed-new-binding
cleanup, earlier successful sources, private-cache isolation and atomic activation.
The separate source-ID-to-repository map is required because several sources can share one binding.

**Minimal repair and validation.** Derive location from the existing runtime through package-private access;
remove `provisionalLocations`, `locationAdded` and paired bookkeeping. Preparation, resolution, adoption
and activation are already synchronized on the provider; no independent location publication is required.
Preserve shared-source/failure tests in
[ProxyAwareNativeGitRepositoryProviderTest](src/test/java/pro/deta/orion/git/proxy/ProxyAwareNativeGitRepositoryProviderTest.java),
adoption tests and
[PersistentProxyActivationTest](src/test/java/pro/deta/orion/git/proxy/PersistentProxyActivationTest.java)'s
failed-candidate and stale-handle cases.

**Alternatives and consequences.** A new holder or generic registry would only relocate complexity.
Keep provisional/active phases and bootstrap credential lifetime; they are distinct verified contracts.

**Confidence and priority.** High from writes, readers and rollback paths. P2 duplicated authoritative state,
straightforward local repair without a configuration or persistence change.

## 4. A single-consumer publication helper repeats full closure traversal

**Problem and evidence.**
[Existing-object fetch branch](src/main/java/pro/deta/orion/git/proxy/NativeBootstrapGitFetcher.java#L37)
checks complete object closure, then calls
[NativeFetchedRefPublisher](src/main/java/pro/deta/orion/git/proxy/NativeFetchedRefPublisher.java#L18), which
checks it again.
Only the fetcher calls this helper in production, for existing targets and freshly downloaded packs.
An upstream rewind to already available history therefore traverses the same graph twice back-to-back.

**Contract.** Before exposing a fetched ref, all referenced objects must exist. Preserve pack-before-ref
publication, expected-old CAS, safe diagnostics and conflict classification.
Objects are immutable and no in-process removal path was found; repeating the positive check is not a lock.

**Minimal repair and validation.** Keep one fetch-owned flow: check completeness; fetch/persist and recheck
only if initially incomplete; then perform common CAS publication. Inline result translation and delete
the helper, without an `alreadyValidated` flag or unchecked publication API.
Retain
[NativeBootstrapGitFetcherTest](src/test/java/pro/deta/orion/git/proxy/NativeBootstrapGitFetcherTest.java)'s
initial fetch, rewind,
missing-tree and concurrent-ref-change cases. Move helper-specific tests to that actual flow; preserve the
already-complete target's no-download behavior and visible CAS failures.

**Alternatives and consequences.** Removing post-download validation would weaken integrity.
Keep `complete object validation` diagnostics, CAS failures as CONFLICT and other publication failures as
UNAVAILABLE. No wire or persisted-format change is necessary.

**Confidence and priority.** High from the live branch/helper call chain; not benchmarked.
P2 repeated graph work with a small ownership simplification.
