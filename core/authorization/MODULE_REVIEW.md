# Module Review: core/authorization

## 2. A read-only branch grant can broaden a different write grant

**Problem.** Combine {REPOSITORY=project, WRITE=true, BRANCH=dev} with a read-only
{REPOSITORY=project, BRANCH=main}. Push to main passes repository-write authorization using the first grant,
then branch authorization using the second. Neither grant permits writing main.

**Sources.** [Parent write selection and branch evaluation](src/main/java/pro/deta/orion/auth/check/rule/BranchAccessRules.java#L45),
[operation-independent branch grants](src/main/java/pro/deta/orion/auth/check/rule/GrantAccess.java#L39),
[beforeUpdate enforcement](../../net/git-transport/src/main/java/pro/deta/orion/transport/git/auth/AuthenticatedRepositoryAccessHook.java#L94),
[access-rule tests](src/test/java/pro/deta/orion/auth/check/AccessRulesTest.java), and
[hook tests](../../net/git-transport/src/test/java/pro/deta/orion/transport/git/auth/AuthenticatedRepositoryAccessHookTest.java).
Existing branch-push cases use a single branch/write grant and miss mixed read/write grants.

**Documented behavior.** The rule's class comment associates branch restrictions with repository grants;
[README](../../README.md#L522) requires repository/branch grants and write permission for push. No contract
allows a read-only grant to contribute additional writable branches.

**Contract.** Branch permission for push must come from a repository grant applicable to write. Preserve
implicit repository-read behavior and unrelated authorization rules.

**Minimal repair.** Evaluate branch scope using grants applicable to the requested operation, within the
existing rule/helper structure. Test mixed read/write grants through push() and beforeUpdate(). Preserve or
explicitly decide unrestricted-plus-restricted grant behavior before changing that separate combination case.

**Alternatives and consequences.** A global branch allowlist retains the escalation. Replacing the entire
policy engine is unnecessary. Filtering by operation changes unintended cross-grant write expansion without
requiring new public APIs or persisted ACL representation.

**Confidence.** High in the public hook's acceptance of the counterexample; excluded engine code is unnecessary
to establish this decision. Static inspection only.

**Priority signals.** Importance: high because a read-only branch can become writable. Repair ease:
high-to-medium, a local operation-aware correction with grant-combination coverage.
