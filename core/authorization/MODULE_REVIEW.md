# Module Review: core/authorization

## 1. Repository matching substitutes another identity for a literal dot

**Problem.** A grant for team/api.v1 is rewritten to team/api_v1, authorizing a different valid repository and
denying the intended one. The wildcard in team/* also excludes valid dotted names.

**Sources.** [Lossy matching](src/main/java/pro/deta/orion/auth/check/MatcherUtils.java#L15),
[grant consumer](src/main/java/pro/deta/orion/auth/check/rule/GrantAccess.java#L23),
[SSH/HTTP access hook](../../net/git-transport/src/main/java/pro/deta/orion/transport/git/auth/AuthenticatedRepositoryAccessHook.java#L38),
[pack authorization](../../net/http-core/src/main/java/pro/deta/orion/transport/http/OrionGitPackfileRoute.java#L113),
[valid dotted-name test](../schema/src/test/java/pro/deta/orion/schema/orion/RepositoryNameTest.java#L14),
and [matcher/access tests](src/test/java/pro/deta/orion/auth/check/AccessRulesTest.java).
Existing wildcard cases check slash containment but not dotted/underscore collisions.

**Documented behavior.** [README](../../README.md#L524) defines * as matching one path segment.
[The canonical identity contract](../../docs/plans/tasks/12_http-core-hardening/02_typed-route-matching.md#L48)
requires authorization and storage to use the same identity and accepts dotted names.

**Contract.** Preserve literal repository identity and slash-bounded wildcards. Replacing valid literal
characters with underscores is not a required compatibility behavior.

**Minimal repair.** Escape literal pattern text and expand only *, including valid segment punctuation without
crossing /. Keep the matcher API/owner. Verify exact dotted match, rejection of underscore collision, dotted
wildcards and nested-path rejection through access rules or the allowed transport hook.

**Alternatives and consequences.** Restricting repository names would remove supported names and affect storage
and wire consumers. Adding dot without literal escaping makes it a regex wildcard. Grants accidentally relying
on substitution must cease granting that unintended access. No new policy engine or configuration is needed.

**Confidence.** High from deterministic matching and canonical-name tests. Git engine/storage implementations
were excluded; the supported finding concerns the authorization decision at inspected callers.

**Priority signals.** Importance: high because the matcher grants access to another repository identity.
Repair ease: high, a local matcher correction with behavioral boundary cases.

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
