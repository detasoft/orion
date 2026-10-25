# Module Review: `core/authorization`

## 7. Branch-scoped writers can mutate tags outside their branch grants

**Problem.** A writer with `REPOSITORY=project`, `READ_WRITE=true`, and `BRANCH=dev` passes the repository
write check. Creating or deleting `refs/tags/v1` then bypasses branch authorization because the per-update
hook only applies branch grants to `refs/heads/`. The same grant cannot update `refs/heads/main`, so its
branch restriction has different effects across ref namespaces.

**Sources.** [RepositoryAccessRules.evaluateWrite()](src/main/java/pro/deta/orion/auth/check/rule/RepositoryAccessRules.java)
checks repository and operation expressions before the
[access hook](../../net/git-transport/src/main/java/pro/deta/orion/transport/git/auth/AuthenticatedRepositoryAccessHook.java)
checks each update. `beforeUpdate()` checks branch push grants only for `refs/heads/`; for other refs it
checks only the repository force grant when `force` is true.
[NativeGitReceivePack.complete()](../../git/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/receive/NativeGitReceivePack.java)
classifies updates as force only when both old and new IDs exist, leaving tag creation and deletion outside
that check. [Hook tests](../../net/git-transport/src/test/java/pro/deta/orion/transport/git/auth/AuthenticatedRepositoryAccessHookTest.java)
cover branch and force checks but do not establish tag creation/deletion permissions for scoped writers.

**Documented behavior.** Branch read and write grants exist, and branch-limited readers hide all tags.
No user-facing contract was found defining whether branch-scoped write grants authorize tag mutations or
which grant must authorize branch deletion and tag rewrites.

**Contract.** Define ref mutation permissions through grants, including the meaning of branch qualifiers for
tag operations. Preserve repository ownership, scoped DENY precedence, old-ID conflict detection, and atomic
publication. Existing grants should not silently authorize a different ref namespace through a preliminary
repository-wide check.

**Minimal repair.** Define the grant requirements for tag creation, update, and deletion, then enforce them
at the existing per-update hook. Classify destructive changes explicitly if they require additional grants.
Test ordinary allowed branch updates, rejected unrelated branches, and tag mutations with branch-limited
and repository-wide writers.

**Alternatives and consequences.** Excluding branch-qualified grants from tag authorization reuses current
repository grants. Separate tag or deletion action keys allow finer privileges but expand the grant contract
and require an explicit decision about existing writers. A separate repository safety-switch model would
duplicate the selected grant authority.

**Confidence.** High for the tag bypass in the current execution path; intended tag and deletion grant
semantics remain undecided. No destructive push reproduction was run.

**Priority signals.** Importance: high for writers expected to be confined to selected branches. Repair ease:
medium after the grant semantics are settled; the existing update hook provides the enforcement boundary.

## 8. System and organization identities interpret the same read grant differently

**Problem.** `{REPOSITORY=acme/team/repo, CREATE=true}` grants a system identity read access to an existing
repository. The same expressions do not grant an organization identity read access. The discrepancy also
applies to `FORCE`, `ADMIN`, and `SHUTDOWN` qualified by `REPOSITORY`: system read checks only the repository
expression, while scoped read excludes grants dedicated to those actions.

**Sources.** `RepositoryAccessRules.evaluateRead()` in the
[repository rules](src/main/java/pro/deta/orion/auth/check/rule/RepositoryAccessRules.java) and
`GrantAccess.matchesRepositoryAction()` in the
[scoped matcher](src/main/java/pro/deta/orion/auth/check/rule/GrantAccess.java) own separate interpretations.
[Hierarchical tests](src/test/java/pro/deta/orion/auth/check/HierarchicalAccessRulesTest.java), especially
`repositoryQualifierDoesNotTurnOtherActionsIntoReadPermission`, establish the scoped behavior.
[System tests](src/test/java/pro/deta/orion/auth/check/AccessRulesTest.java) preserve implicit read from a bare
repository expression, but do not compare identical operation-qualified grants across identity kinds.

**Documented behavior.** [README](../../README.md) documents identity boundaries and combined `READ_WRITE`
access. No user-facing explanation of this action-to-read discrepancy was found. Bare repository grants are
intentional: invitation onboarding gives organization users such a read grant, as README explicitly states.

**Contract.** Preserve the system/organization identity boundary, organization DENY precedence, and documented
onboarding behavior. Decide whether action-specific grants should imply read before unifying matching.

**Minimal repair.** Define one action-to-read truth table and reuse its expression semantics in the existing
helper, retaining separate scope/deny handling. Test identical grants with both identity kinds and retain
coverage for bare repository grants and `READ_WRITE`.

**Alternatives and consequences.** Documenting an intentional difference preserves permissions but retains
two policy interpretations. Harmonizing toward scoped behavior can revoke implicit system read; harmonizing
toward system behavior broadens organization read. Neither change should happen as incidental refactoring.

**Confidence.** High for the difference; intended compatibility semantics require a product decision.

**Priority signals.** Importance: medium to high for grant review and least-privilege administration. Repair
ease: medium after the contract choice; no new role or policy abstraction is needed.

## 9. System SSH sessions retain revoked privileges while organization checks read current grants

**Problem.** Authenticate a system SSH user, revoke their repository/ADMIN grant or remove the user, and keep
the connection open. Commands and Git operations using its existing `UserIdentity` continue evaluating the
grants captured at authentication. Organization identities instead read current configuration for repository
decisions. Current-document administration checks can also disagree with application-admin checks on the
same retained system identity.

**Sources.** [InternalUserImpl](src/main/java/pro/deta/orion/auth/InternalUserImpl.java) stores system grants
and an organization configuration supplier.
`OrionAccessControlServiceImpl.createUserIdentity()` and `canAdminister()` in the
[ACL service](../acl/src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java) respectively capture
system grants and re-read a document.
[SSH authentication](../../net/git-transport/src/main/java/pro/deta/orion/transport/git/auth/OrionSshAuthenticator.java)
stores the identity on the session; the
[legacy command catalog](../../net/git-transport/src/main/java/pro/deta/orion/transport/git/command/LegacySshCommandCatalog.java)
uses application-admin rules. [Hierarchical tests](src/test/java/pro/deta/orion/auth/check/HierarchicalAccessRulesTest.java)
verify live organization revocation; [ACL tests](../acl/src/test/java/pro/deta/orion/acl/OrionAccessControlServiceImplTest.java)
verify authentication snapshot consistency, which is a different guarantee.

**Documented behavior.** [UserIdentity](src/main/java/pro/deta/orion/auth/UserIdentity.java) explicitly describes
this split. [README](../../README.md) describes immediate ACL activation, without defining whether already
authenticated system sessions retain removed authority. HTTP token verification reconstructs system identity
per request; that does not settle long-lived SSH revocation.

**Contract.** Define the revocation boundary for existing sessions while preserving a coherent snapshot per
individual decision. Live revocation need not imply tearing down an operation already in progress.

**Minimal repair.** Decide whether the next SSH command/Git operation must re-resolve the system principal.
If required, reuse the current configuration owner and system grant resolver at that boundary. Test an open
session across grant removal and user deletion, plus a successful operation before revocation.

**Alternatives and consequences.** Explicit session-lifetime grants preserve current behavior but need a
documented revocation mechanism. A separate live cache or permission registry would duplicate authoritative
state unnecessarily. Re-resolving must retain root lock/recovery restrictions and authentication consistency.

**Confidence.** High for retained versus live grants; no connected SSH revocation experiment was run.

**Priority signals.** Importance: high when emergency revocation is expected to affect open connections.
Repair ease: medium, because several session consumers share the identity contract.

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
