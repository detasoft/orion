# Module Review: `core/authorization`

## 12. Automatic annotated-tag delivery bypasses ref visibility filtering

**Problem.** A branch-restricted reader fetches an allowed commit with `include-tag`. Discovery hides all
tags, but pack preparation scans every tag ref and adds annotated-tag objects pointing to selected objects.
Those objects contain the tag name, tagger, message, and potentially a signature. Hiding a ref advertisement
therefore does not hide its annotation from automatic delivery.

**Sources.** [NativeGitRepositoryContext.visibleRefs()](../../net/git-transport/src/main/java/pro/deta/orion/transport/git/NativeGitRepositoryContext.java)
exposes only permitted heads and HEAD for restricted readers. [FetchPack.prepare() and includeTags()](../../git/git-parser/src/main/java/pro/deta/orion/git/parser/v2/fetch/FetchPack.java)
instead use the complete repository index and `refs/tags/*`, without consulting that visibility decision.
[GitBlockingWireSession](../../git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireSession.java)
advertises `include-tag`; [NegotiationContext](../../git/git-parser/src/main/java/pro/deta/orion/git/parser/v2/fetch/NegotiationContext.java)
also accepts it in protocol v2. [FetchPlanTest](../../git/git-parser/src/test/java/pro/deta/orion/git/parser/v2/command/FetchPlanTest.java)
exercises pack selection, but no combined restricted-reader/hidden-annotation regression was found.

**Documented behavior.** The selected branch-restricted discovery behavior hides every tag, regardless of
target reachability. No separate user-facing contract was found for automatic annotated-tag payloads.

**Contract.** Clarify whether hiding all tags covers automatic annotation delivery as well as discovery.
Preserve allowed branch contents and annotated-tag delivery for unrestricted readers. This path only adds
annotations whose targets are selected; it does not by itself establish access to unrelated branch history.

**Minimal repair.** If annotations must also stay hidden, select automatically included tags through the
existing repository visibility boundary. Cover legacy and v2 fetches, nested annotated tags, and unrestricted
readers. Suppressing the advertised capability alone is insufficient because v2 accepts the request flag.

**Alternatives and consequences.** Explicitly documenting discovery-only hiding retains annotation delivery
and its metadata exposure. Filtering automatic tag selection reuses the existing visibility owner without
adding a second grant or policy model.

**Confidence.** High for the static delivery path; confidentiality impact depends on the annotation contract.
No wire-level reproduction was run during this review.

**Priority signals.** Importance: medium to high if hidden tags carry private names or messages. Repair ease:
medium; advertisement filtering and pack selection currently consume different ref views.

## 13. Branch-scoped writers can mutate other ref namespaces

**Problem.** A writer confined to `BRANCH=dev` can submit creation or deletion of `refs/notes/commits` or
another valid custom ref. The repository write grant passes the opening check, but these mutations reach
publication without the unrestricted-write requirement enforced for tags. Forced changes in these namespaces
also use the repository FORCE rule rather than its unrestricted counterpart.

**Sources.** [AuthenticatedRepositoryAccessHook.beforeUpdate()](../../net/git-transport/src/main/java/pro/deta/orion/transport/git/auth/AuthenticatedRepositoryAccessHook.java)
checks branch grants for `refs/heads/*` and unrestricted grants for `refs/tags/*`. Other ref names skip the
ordinary write check. [NativeGitReceivePack.complete()](../../git/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/receive/NativeGitReceivePack.java)
classifies creation and deletion as non-force, then publishes accepted updates.
[RefId.requireFullName()](../../git/git-parser/src/main/java/pro/deta/orion/git/parser/v2/id/RefId.java)
permits custom `refs/*` namespaces. [Hook tests](../../net/git-transport/src/test/java/pro/deta/orion/transport/git/auth/AuthenticatedRepositoryAccessHookTest.java)
cover tag confinement but do not cover the equivalent custom-ref mutations.

**Documented behavior.** [README](../../README.md) defines branch restrictions and unrestricted permissions
for tag mutations. No user-facing grant contract was found for notes and custom ref namespaces.

**Contract.** Decide whether branch-scoped writers may change repository-wide metadata outside heads.
Preserve allowed branch updates, independent READ_WRITE/FORCE scopes, scoped DENY precedence, and atomic
publication. The intentionally unauthenticated native Git transport remains outside these user ACL rules.

**Minimal repair.** If non-head refs require repository-wide authority, generalize the existing tag checks
to every supported non-head namespace. Test creation, deletion, and forced replacement of notes/custom refs
for restricted and unrestricted writers, including atomic batches.

**Alternatives and consequences.** Allowing notes to branch-scoped writers needs an explicit contract about
shared metadata. New namespace grant keys would offer finer control but expand the grant model without a
current requirement. Reusing unrestricted READ_WRITE/FORCE avoids that expansion.

**Confidence.** High for the missing checks; custom-ref permission semantics require a product decision.
No SSH or HTTP mutation reproduction was run during this review.

**Priority signals.** Importance: medium to high for writers expected to remain confined to selected branches.
Repair ease: high once the namespace contract is settled; the existing per-update hook owns enforcement.
