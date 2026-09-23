# Module Review: `git/git-parser`

## 15. Delta resolution decompresses stored entries twice

**Problem and evidence.**
[Resolver content reads](src/main/java/pro/deta/orion/git/parser/v2/pack/GitPackObjectResolver.java#L120)
at lines 120, 140 and 156 use the offset-based `IndexedPack.readObject` with `ContentGitObjectRead`.
[readStored](src/main/java/pro/deta/orion/git/parser/v2/pack/IndexedPack.java#L557) first sends compressed bytes
through
[ZlibBoundaryByteSource](src/main/java/pro/deta/orion/git/parser/v2/pack/ZlibBoundaryByteSource.java#L39),
which inflates and discards
content to discover the compressed boundary. `ContentGitObjectRead` then inflates those bytes again.
Delta instructions and bases incur this repeated work, amplified by long chains.

**Contract.** Ingestion completes before this resolver runs, so indexed entry boundaries are available.
Preserve malformed-zlib/delta rejection, full draining after early callbacks, resource cleanup, and unchanged
pack bytes when no external bases are appended. Generic raw reads before the trailer remain supported.

**Minimal repair and validation.** Use the existing
[bounded readObject overload](src/main/java/pro/deta/orion/git/parser/v2/pack/IndexedPack.java#L329)
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
