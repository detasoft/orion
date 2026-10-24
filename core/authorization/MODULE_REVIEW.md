# Module Review: `core/authorization`

## 6. Boolean-looking privilege values are accepted but ignored

**Problem.** In an ALLOW grant, `READ_WRITE=false`, `FORCE=false`, or `ADMIN=false` still activates the
privilege: operation matching tests for the key's presence. An administrator editing XML can therefore enable
the exact operation they attempted to disable. A false value in a DENY grant likewise remains an active deny.

**Sources.** [GrantMatcher.of(key)](src/main/java/pro/deta/orion/auth/check/GrantMatcher.java) installs an
always-true value matcher. Consumers include the
[repository rules](src/main/java/pro/deta/orion/auth/check/rule/RepositoryAccessRules.java),
[scoped matcher](src/main/java/pro/deta/orion/auth/check/rule/GrantAccess.java), and
[application rules](src/main/java/pro/deta/orion/auth/check/rule/ApplicationAccessRules.java).
The [XML mapper](../schema/src/main/java/pro/deta/orion/schema/orion/v2/OrionV2Mapper.java) and
[expression record](../schema/src/main/java/pro/deta/orion/schema/acl/GrantExpression.java) retain arbitrary
string values. Existing [access tests](src/test/java/pro/deta/orion/auth/check/AccessRulesTest.java) use `true`
for operation keys and do not establish false-value behavior.

**Documented behavior.** [README](../../README.md) uses `READ_WRITE` and API `readWrite: true` to represent
permission. No explicit presence-only contract or validation rule for false operation values was found.

**Contract.** Persisted operation values must have an unambiguous meaning. Resource patterns such as
`REPOSITORY`, `BRANCH`, and `CONNECTION` must retain their existing matching semantics.

**Minimal repair.** Decide between accepting only canonical `true` operation values and making false values
inactive. Enforce that decision at the existing document-validation/matching boundaries for both system and
organization grants. Test ALLOW, DENY, and invalid values behaviorally, including `ADMIN`.

**Alternatives and consequences.** Rejecting false values is a smaller fail-closed configuration change;
interpreting booleans preserves editable enable/disable semantics but changes existing grant evaluation.
Both require checking persisted XML consumers. A second privilege representation is unnecessary.

**Confidence.** High for current matching and accepted model values; intended value semantics need a decision.

**Priority signals.** Importance: high for accidentally enabled write, force, or administration. Repair ease:
medium, because the correction is local but persisted configuration semantics must be settled first.

## 7. Repository safety policy is persisted without enforcement in receive-pack

**Problem.** A configured repository can have `allowForcePushes=false`, `allowBranchDeletes=false`, and
`allowTagRewrites=false`, yet receive-pack does not consult these fields. A user with write and force grants
can still perform the corresponding updates. Deletion is not classified as force, and non-head refs skip
branch push checks, so restricting a writer to a branch does not restrict ordinary tag creation/deletion.

**Sources.** [RepositoryPolicy](../schema/src/main/java/pro/deta/orion/schema/orion/v2/RepositoryPolicy.java)
defines false safety defaults and is serialized by the
[mapper](../schema/src/main/java/pro/deta/orion/schema/orion/v2/OrionV2Mapper.java).
`NativeGitReceivePack.complete()` in the
[receive path](../../git/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/receive/NativeGitReceivePack.java)
computes force only when old and new IDs both exist, then calls the
[access hook](../../net/git-transport/src/main/java/pro/deta/orion/transport/git/auth/AuthenticatedRepositoryAccessHook.java).
That hook checks `refs/heads/` and force, without loading repository policy.
[Hook tests](../../net/git-transport/src/test/java/pro/deta/orion/transport/git/auth/AuthenticatedRepositoryAccessHookTest.java)
exercise branch/force grants without false-policy deletion/tag cases.

**Documented behavior.** The
[access-policy plan](../../docs/plans/tasks/11_git/07_access-policy-and-protected-refs.md) explicitly identifies
force, branch deletion, and tag rewrites as operations needing policy. Its proposed architecture is future
work, not evidence that current policy fields are enforced. No current grant-to-policy precedence contract
was found.

**Contract.** Stored restrictions must not imply protection absent from the execution path. Decide the
relationship between repository safety switches and user grants before implementing enforcement. Preserve
old-ID conflict detection and atomic publication behavior.

**Minimal repair.** Resolve precedence, then enforce configured restrictions at the existing per-update
boundary, with explicit classification of deletion and tag changes. Test ordinary permitted pushes and
denied force/delete/rewrite operations. Do not implement the plan's entire proposed service hierarchy.

**Alternatives and consequences.** Removing unsupported policy fields avoids false assurances but changes the
persisted contract and loses promised controls. Merely adding more ACL flags leaves the existing stored
switches inert. Tag behavior needs an explicit decision rather than treating tags as branches accidentally.

**Confidence.** High for absent receive-path policy consumption; no destructive push reproduction was run.

**Priority signals.** Importance: high, because configured safety restrictions are ineffective. Repair ease:
medium to low until precedence and tag/delete semantics are defined; then reuse the current update hook.

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

## 11. Ref advertisements expose branches and tags outside the subject's read scope

**Problem.** Give a repository reader access only to `main`, with another branch named `secret` and a tag
pointing into its history. Legacy advertisement and protocol v2 `ls-refs` still disclose both names and object
IDs. Annotated-tag peeling and `HEAD`/symref output can additionally disclose a hidden target. Rejecting a
later fetch does not remove metadata already sent during discovery.

**Sources.** `GitBlockingWireSession.legacyAdvertisement()` in the
[wire session](../../git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireSession.java)
and `RefsCommand.action()` in the
[v2 refs command](../../git/git-parser/src/main/java/pro/deta/orion/git/parser/v2/command/RefsCommand.java)
enumerate an unrestricted `RefSelection.All` snapshot. Both serve real SSH/HTTP clients.
`NativeGitRepositoryContext.checkFetchAccess()` in the
[repository context](../../net/git-transport/src/main/java/pro/deta/orion/transport/git/NativeGitRepositoryContext.java)
checks branch reachability only when objects are requested. Existing
[wire tests](../../net/git-transport/src/test/java/pro/deta/orion/transport/git/GitBlockingWireSessionTest.java)
exercise advertisement and `ls-refs` using an unrestricted access hook; they do not establish subject-specific
filtering. [GrantKey](../schema/src/main/java/pro/deta/orion/schema/acl/GrantKey.java) has no separate tag action.

**Documented behavior.** [README](../../README.md) requires repository and branch grants within organization
boundaries. The [access-policy plan](../../docs/plans/tasks/11_git/07_access-policy-and-protected-refs.md)
calls for filtering upload-pack and receive-pack advertisements, with `HEAD` and peeled targets visible only
when their targets are visible. Those planned guarantees are not implemented by the current enumerators.

**Contract.** Advertise only refs allowed by the subject's applicable read policy. Preserve the wire format,
request prefixes, unrestricted-reader behavior, and one coherent refs snapshot. Hide all tags for readers
whose access is restricted by branch, including lightweight and annotated tags whose targets are reachable
from an allowed branch. A separate tag grant is not part of the authorization model. Hidden names and IDs
must not escape through `HEAD`, symrefs, or peeling.

**Minimal repair.** Add one subject-aware ref-filtering boundary reused by legacy advertisement and v2
`ls-refs`, including receive-pack discovery. Reuse existing branch/read rules and the unrestricted-read check
rather than implementing policy separately in each transport. Cover visible and denied branches, hidden
lightweight and annotated tags even with shared reachable targets, hidden `HEAD` targets, and unrestricted
readers through wire tests.

**Alternatives and consequences.** Filtering only `ls-refs` leaves legacy clients and receive-pack discovery
exposed. Filtering names without associated `HEAD`/peeled metadata is incomplete. A broad new Git policy
service is unnecessary for this boundary. An unborn symbolic `HEAD` must also respect its branch rule.

**Confidence.** High for unrestricted enumeration in both production paths; static inspection only, without
a subject-restricted wire reproduction yet.

**Priority signals.** Importance: high for ref-name and object-ID confidentiality. Repair ease: medium, because
the shared repository boundary must cover two protocol versions and their metadata without duplicating ACLs.
