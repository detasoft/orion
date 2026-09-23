# Module Review: `git/git-native-proxy`

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
