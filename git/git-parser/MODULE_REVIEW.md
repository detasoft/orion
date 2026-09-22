# Module Review: `git/git-parser`

## 14. Pack completion and publication duplicate checksum verification

**Problem and evidence.**
[IndexedPack.digest](src/main/java/pro/deta/orion/git/parser/v2/pack/IndexedPack.java#L205)
and [GitPackStorage.digest](src/main/java/pro/deta/orion/git/parser/v2/storage/GitPackStorage.java#L361)
independently implement
the same chunked pack hashing, EOF and zero-progress checks. Duplicate publication also supplies its own
exact-read and SHA-1 helpers; it rereads the trailer already loaded by `IndexedPack.open`.
Every completed thin pack and reuse of an already published pack depends on keeping these algorithms aligned.

**Contract.** Existing published bytes and trailer must be verified before reuse; cached PackId equality
cannot replace rehashing. Completion must recalculate the checksum when external bases change the pack.
Publication locking, atomic moves and directory synchronization remain required.

**Minimal repair and validation.** Put verification on the existing `IndexedPack` owner and reuse its
digest/trailer machinery from publication. Remove the second digest/exact-read/SHA-1 implementation;
a utility framework or extra state is unnecessary. Preserve
[PackCompletionTest](src/test/java/pro/deta/orion/git/parser/v2/storage/PackCompletionTest.java) and
[PackPublicationTest](src/test/java/pro/deta/orion/git/parser/v2/storage/PackPublicationTest.java).
Cover duplicate publication with corrupted existing content and corrupted trailer, retaining the existing
pair and cleaning staging data on failure.

**Alternatives and consequences.** Sharing only the digest factory leaves duplicate integrity logic;
trusting the index or cached ID weakens corruption detection. No persisted format or wire change is needed.

**Confidence and priority.** High from both live call paths; no runtime reproduction was run.
P2 maintenance issue, medium importance and straightforward local repair.

## 15. Delta resolution decompresses stored entries twice

**Problem and evidence.**
[Resolver content reads](src/main/java/pro/deta/orion/git/parser/v2/pack/GitPackObjectResolver.java#L120)
at lines 120, 140 and 156 use the offset-based `IndexedPack.readObject` with `ContentGitObjectRead`.
[readStored](src/main/java/pro/deta/orion/git/parser/v2/pack/IndexedPack.java#L552) first sends compressed bytes
through
[ZlibBoundaryByteSource](src/main/java/pro/deta/orion/git/parser/v2/pack/ZlibBoundaryByteSource.java#L39),
which inflates and discards
content to discover the compressed boundary. `ContentGitObjectRead` then inflates those bytes again.
Delta instructions and bases incur this repeated work, amplified by long chains.

**Contract.** Ingestion completes before this resolver runs, so indexed entry boundaries are available.
Preserve malformed-zlib/delta rejection, full draining after early callbacks, resource cleanup, and unchanged
pack bytes when no external bases are appended. Generic raw reads before the trailer remain supported.

**Minimal repair and validation.** Use the existing
[bounded readObject overload](src/main/java/pro/deta/orion/git/parser/v2/pack/IndexedPack.java#L324)
with `dataEnd(entry.offset())` for these three resolver reads; retain `ContentGitObjectRead`.
Their callbacks do not depend on the bounded path's OFS-to-REF metadata normalization.
Preserve
[GitPackObjectResolverTest](src/test/java/pro/deta/orion/git/parser/v2/pack/GitPackObjectResolverTest.java)
cases for forward references, OFS deltas, external bases, deep chains,
malformed instructions, memory/disk storage and pack versions 2/3. Measure decompression work with a focused
profile instead of asserting implementation structure.

**Alternatives and consequences.** Deleting the generic boundary reader would weaken pre-trailer reads
and unread-tail validation covered by IndexedPackTest. The local call-site change avoids that API change.

**Confidence and priority.** High for repeated work; medium-high for the bounded substitution.
P2 CPU cost, especially on delta chains; no runtime performance measurement was made.
