# Module Review: `core/authorization`

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
