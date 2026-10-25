# Module Review: `core/authorization`

## 10. Local repository creation does not establish the organization scope needed for later access

**Problem.** An organization user with inherited CREATE and READ_WRITE can create `acme/team/new` when
`acme/team` exists but `new` is absent from configuration. Local creation creates storage only. Subsequent
read/write decisions deny the user because the repository is still missing from the organization document.
The S3 path registers a repository entry, so authorization after creation depends on the storage path.

**Sources.** [ScopedAccess.assignedGrants()](src/main/java/pro/deta/orion/auth/check/ScopedAccess.java) rejects
a missing repository unless its caller requests `allowMissingRepository`;
[GrantAccess](src/main/java/pro/deta/orion/auth/check/rule/GrantAccess.java) enables that only for CREATE.
`ConfiguredStorageManagement.createRepository()` in
[storage management](../../net/git-transport/src/main/java/pro/deta/orion/transport/git/ConfiguredStorageManagement.java)
updates the document through `bind()` only for S3.
[Git repository opening](../../net/git-transport/src/main/java/pro/deta/orion/transport/git/DefaultGitNativeRepositoryService.java)
also creates a missing repository directly through the provider.
[Hierarchical tests](src/test/java/pro/deta/orion/auth/check/HierarchicalAccessRulesTest.java) explicitly allow
CREATE but deny READ on an absent entry; the
[S3 management test](../../net/http-core/src/test/java/pro/deta/orion/transport/http/S3RepositoryManagementIT.java)
exercises the registered storage path, not the equivalent local lifecycle.

**Documented behavior.** [README](../../README.md) requires an organization/team/repository identity and
describes repository creation. No documented rule says local creation succeeds while leaving its creator's
otherwise applicable read/write grants unusable until a separate configuration edit.

**Contract.** Keep missing-team and cross-organization denial. A successful supported creation must leave
repository existence and scoped authorization consistent; it must not silently grant new privileges.

**Minimal repair.** Decide whether organization-local creation must register the repository in the existing
configuration transaction or require pre-registration before creating storage. Apply the same decision to
HTTP creation and first-push creation. Cover creation followed by authorized read/write and a denied sibling.

**Alternatives and consequences.** Pre-registration is smaller but changes supported creation behavior.
Allowing authorization from storage existence weakens the current document-owned scope contract and must be
explicitly approved. A second repository ownership registry would duplicate configuration state.

**Confidence.** High from the creation and decision paths; no local creation/read lifecycle test was run.

**Priority signals.** Importance: medium to high, because successful creation produces an inaccessible
resource for the intended principal. Repair ease: medium, with configuration/storage lifecycle implications.
