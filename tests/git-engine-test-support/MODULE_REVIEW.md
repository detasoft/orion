# Module Review: `tests/git-engine-test-support`

## 4. Snapshot capture retains an unused compatibility overload

**Problem and evidence.**
[RepositorySnapshot.capture(Path, String)](src/main/java/pro/deta/orion/git/workflow/RepositorySnapshot.java#L51)
is deprecated, ignores its `ignoredHead` argument and forwards to `capture(Path)`.
Repository-wide searches found no caller. Real
[GitWorkTree](src/main/java/pro/deta/orion/git/workflow/GitWorkTree.java#L68) and
[GitServer](src/main/java/pro/deta/orion/git/workflow/GitServer.java#L29) consumers use the path overload;
Orion adapters also use the repository overload. The unused signature suggests caller-supplied HEAD
affects the observed snapshot.

**Contract.** HEAD, refs, history, tree modes, object IDs and content hashes come from the observed
repository. No compatibility requirement for the discarded argument was found; repository rules require
removing replaced internal paths.

**Minimal repair and validation.** Delete only the deprecated overload.
Preserve
[snapshot behavior coverage](src/test/java/pro/deta/orion/git/workflow/GitInteroperabilityMatrixTest.java#L237)
and [JGit observation](src/test/java/pro/deta/orion/git/workflow/JGitReferenceAdaptersTest.java#L79).
Compile dependent modules to catch missed consumers; add no method-absence test.

**Alternatives and consequences.** An alias or migration flag preserves an unused path.
Deletion affects only a source signature with no in-repository consumers; supported capture behavior
and snapshot representation remain unchanged.

**Confidence and priority.** High from the forwarding body and repository-wide callers.
P3 trivial deletion; no runtime uncertainty in the overload's behavior.
