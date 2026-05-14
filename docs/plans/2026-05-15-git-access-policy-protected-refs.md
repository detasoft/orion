# Git Access Policy and Protected Refs

## Goal

Add a detailed plan for Git repository access policy, protected refs, hidden refs,
and push/fetch authorization across JGit-backed and native Git paths.

Native upload-pack, receive-pack, `saveFiles`, mirroring, remote single-file
push, and migration all need consistent decisions about which refs are visible,
which refs are writable, when force updates are allowed, whether tags can be
changed, and how branch-scoped ACL grants map to raw Git object requests.

This plan defines a policy layer that sits above ref/object storage and below
protocol response generation. It should preserve current fetch access semantics,
add explicit push/ref-update policy, and keep security-sensitive decisions
fail-closed.

## Current State

Repository-level ACL rules exist for create, read, and write grants.

Branch-level fetch rules exist through `BranchAccessRules.fetch()`.

`GitFetchAccessRules.everyWantedObjectAllowed()` evaluates upload-pack wants by
resolving wanted object ids to branch names through `GitFetchAccessRequest` and
then checking branch grants.

`GitInternalService` checks repository read/write/create before upload-pack or
receive-pack is delegated to a repository.

`JGitRepository.upload()` calls the configured fetch access check with wanted
object ids and a branch resolver.

`JGitRepository.receive()` delegates push command parsing and ref update policy
mostly to JGit and emits `GitRefUpdate` results.

The native receive-pack plan lists command-level policy hooks:

- branch create;
- branch update;
- branch delete;
- tag create/update/delete;
- force push;
- protected refs;
- pack and command limits.

The native upload-pack and reachability plans mention hidden refs, unauthorized
wants, and branch resolution, but there is no single Git policy model that owns
these rules.

## Non-Goals

Do not implement ACL storage, authentication, token scopes, or the generic
authorization engine in this plan.

Do not implement ref storage, receive-pack, upload-pack, pack parsing, or
repository events here. This layer is called by those services.

Do not remove existing repository-level ACL checks.

Do not make branch policy depend on the commit projection only. Security
decisions must use current refs and canonical reachability or validated fallback.

Do not silently allow force pushes, tag rewrites, hidden ref access, or deletes
because the policy layer is unavailable.

Do not leak hidden ref names or unauthorized object ids in protocol errors.

## Policy Boundary

Introduce a Git-specific policy service:

- `GitRepositoryPolicyService`;
- `GitFetchPolicy`;
- `GitPushPolicy`;
- `GitRefVisibilityPolicy`;
- `GitProtectedRefPolicy`;
- `GitRefUpdatePolicy`;
- `GitRepositoryOperationPolicy`;
- `GitPolicyDecision`;
- `GitPolicyReason`;
- `GitPolicyDiagnostics`.

The policy service should answer:

- can this subject read repository metadata;
- can this subject fetch these wanted objects;
- which refs are visible to this subject;
- can this subject create/update/delete this branch;
- can this subject create/update/delete this tag;
- can this subject force-update this ref;
- can this subject write through internal `saveFiles`;
- can this repository be created on first push;
- which protocol capabilities should be advertised for this subject and repo.

Transport and protocol code should not hard-code these decisions.

## Decision Model

Return structured decisions:

- allowed: boolean;
- reason code;
- sanitized message;
- internal diagnostic message;
- policy id/version;
- matched rule id when available;
- subject id;
- repository name;
- ref name or object id when safe;
- retryability where meaningful.

Reason codes should be stable enough for tests:

- repository-read-denied;
- repository-write-denied;
- repository-create-denied;
- branch-fetch-denied;
- wanted-object-unresolved;
- hidden-ref;
- protected-ref;
- branch-create-denied;
- branch-update-denied;
- branch-delete-denied;
- tag-create-denied;
- tag-update-denied;
- tag-delete-denied;
- force-push-denied;
- non-fast-forward-denied;
- default-branch-delete-denied;
- current-head-delete-denied;
- unsupported-ref-namespace;
- policy-unavailable;
- limit-exceeded.

Protocol-facing messages should use sanitized versions of these reasons.

## Ref Classes

Classify refs before policy evaluation.

Ref classes:

- branch: `refs/heads/*`;
- tag: `refs/tags/*`;
- symbolic `HEAD`;
- internal Orion refs;
- hidden refs;
- notes or custom refs;
- unsupported namespace;
- invalid ref name.

Ref classification should be shared by upload-pack advertisement, receive-pack
command validation, `saveFiles`, mirroring, and migration.

Unknown namespaces should default to hidden or denied unless configuration
explicitly allows them.

## Ref Visibility

Ref visibility controls what a subject can see.

Visibility decisions affect:

- upload-pack advertisement;
- receive-pack advertisement;
- branch resolver for fetch wants;
- error messages;
- mirror ref discovery;
- audit/export logs.

Default rules:

- visible branch refs require repository read and branch permission if branch
  restrictions exist;
- visible tags require repository read and tag visibility policy;
- `HEAD` is visible only if its target is visible or if empty repository policy
  allows unborn `HEAD` advertisement;
- internal refs are hidden by default;
- hidden refs are never leaked in user-visible errors.

The policy should support a repository-level hidden-ref configuration such as
prefixes or explicit ref patterns.

## Fetch Policy

Preserve current fetch access semantics.

Flow:

1. repository read must be allowed;
2. upload-pack parses wants;
3. reachability service resolves wants to visible branches/tags;
4. every wanted object must resolve to at least one ref visible to the subject;
5. branch-restricted grants must allow each resolved branch;
6. unauthorized wants fail before object enumeration and pack generation.

If a wanted object is reachable only from hidden refs, return a sanitized
authorization failure. Do not disclose the hidden ref name.

If a wanted object cannot be resolved because reachability/projection is
unavailable, fail closed unless canonical traversal can complete within limits.

## Wanted Object Resolution

The branch resolver used by `GitFetchAccessRequest` should become a native policy
dependency.

Resolution should provide:

- wanted object id;
- visible refs that reach it;
- hidden refs that reach it only as internal diagnostics;
- whether it is directly a visible tag;
- whether it is not reachable from any advertised ref;
- traversal status.

The policy layer should decide whether one visible ref is enough or every
resolved branch must be checked.

Default: allow when at least one visible, branch-authorized ref reaches the
wanted object, and no configured stricter rule requires all refs.

## Advertisement Policy

Upload-pack and receive-pack should advertise only what the subject can use.

Upload-pack:

- advertise readable visible branches and tags;
- include `HEAD` only when readable;
- include peeled tags only when target visibility allows it;
- omit internal refs;
- omit capabilities not allowed by repository/backend/policy.

Receive-pack:

- advertise refs that are relevant to push decisions;
- hide refs that the subject cannot know about;
- advertise delete/atomic/push-options only when policy and backend support them;
- do not advertise force capability because force is command semantics, not a
  Git capability.

Policy should be evaluated before packet advertisement is written.

## Push Policy

Receive-pack should evaluate command-level push policy before ref updates.

Inputs:

- subject/security context;
- repository;
- current ref snapshot;
- command old id/new id/ref name;
- command type;
- object validation result;
- fast-forward result;
- requested capabilities;
- explicit force marker where the service can infer it;
- repository policy configuration.

Decisions:

- create branch;
- fast-forward branch update;
- non-fast-forward branch update;
- delete branch;
- create tag;
- update tag;
- delete tag;
- update protected ref;
- update hidden/internal ref;
- default branch delete;
- current `HEAD` target delete.

Policy should run before ref compare-and-set and should produce per-command
results for report-status.

## Force Push Policy

Force updates must be explicit.

Default:

- non-fast-forward branch updates are rejected;
- force push is denied unless repository write plus force policy grant matches;
- force update to protected refs is denied unless an even stronger admin policy
  allows it;
- tag rewrites are denied by default.

The policy layer should distinguish:

- non-fast-forward update without force;
- explicit force requested but denied;
- explicit force allowed.

Native receive-pack can infer force when command old id does not match an
ancestor relationship and policy/config says force is required.

## Delete Policy

Deletes should be explicit and conservative.

Default:

- branch deletes are denied unless delete policy allows them;
- tag deletes are denied unless tag delete policy allows them;
- deleting the default branch is denied;
- deleting the branch targeted by `HEAD` is denied unless repository policy
  allows an unborn/default fallback;
- deleting hidden/internal refs is denied to normal users.

Receive-pack should advertise `delete-refs` only after delete policy and ref
store delete support are both ready.

## Tag Policy

Tags need separate rules from branches.

Default:

- tag create can be allowed with repository write when configured;
- tag update is denied;
- tag delete is denied;
- annotated tag target must exist and be visible enough for the actor;
- lightweight tag target must exist;
- protected tag patterns can require admin policy.

This avoids silently rewriting release tags.

## Protected Ref Rules

Add protected ref rules:

- exact ref name;
- prefix;
- glob-like pattern where safe and deterministic;
- default branch marker;
- current `HEAD` target marker;
- tag pattern such as `refs/tags/v*`;
- internal namespace marker.

Protected rule actions:

- block create;
- block update;
- block non-fast-forward;
- block delete;
- require admin;
- require signed/verified actor later;
- require maintenance/migration actor.

Rules should be ordered deterministically and return the matched rule id in
internal diagnostics.

## Internal Operations

Internal operations need policy too.

`saveFiles`:

- repository write required unless bootstrap/internal mode bypass is explicitly
  configured;
- branch create allowed only when policy allows internal creation;
- stale ref conflict is not a policy allow/deny decision;
- protected ref updates require internal policy permission.

Migration:

- migration actor can create refs in the target native repository during import;
- migration should not bypass protected ref policy during cutover unless
  explicitly configured.

Maintenance:

- maintenance can write repair metadata, but should not change branch refs except
  through a separate repair plan and admin approval.

Mirror sync:

- mirror actor can update refs according to mirror policy;
- force/delete/tag behavior follows explicit mirror rules.

## Policy Configuration

Start with a simple repository policy model.

Fields:

- hidden ref patterns;
- protected ref patterns;
- default branch protection;
- allow branch create;
- allow branch delete;
- allow tag create;
- allow tag update;
- allow tag delete;
- allow force push;
- allow internal save create;
- allow first push create;
- receive-pack command count limit;
- pushed pack size limit;
- upload wants limit;
- required actor grants for force/delete/protected refs.

Policy source options:

- global configuration defaults;
- repository metadata override;
- future repository config file;
- ACL grants/scopes;
- hard-coded safe defaults until config exists.

Default policy must be conservative.

## ACL Integration

Keep ACL and Git policy boundaries clear.

ACL answers:

- who the subject is;
- repository read/write/create grant;
- branch restricted grant for fetch;
- future grants for force/delete/protected refs.

Git policy answers:

- how Git ref/update semantics apply to the requested operation;
- whether the operation is safe under repository configuration;
- which refs are visible;
- how to map Git-specific failures to protocol statuses.

The policy service can call ACL rules, but Git protocol code should call the
policy service rather than calling ACL rules directly for every command.

## Token Scope Integration

Application tokens and future OAuth scopes should map into policy.

Possible scopes:

- repository read;
- repository write;
- branch fetch;
- branch push;
- tag create;
- tag update;
- force push;
- delete refs;
- mirror write;
- migration admin.

Token scope enforcement can be phased in after token storage/authentication is
stable. Until then, policy should preserve existing ACL grant behavior.

## Error Mapping

Map policy decisions to caller-specific errors.

Upload-pack:

- unauthorized wants become protocol-safe errors before pack data;
- hidden ref names are redacted;
- missing policy data fails closed.

Receive-pack:

- per-command rejects become `ng <ref> <reason>`;
- pack-level policy failures become `unpack <error>` only when appropriate;
- unsupported capabilities fail before pack ingestion.

`saveFiles`:

- denied policy becomes `GitOperationException` or future typed access exception;
- stale ref remains a conflict, not access denied.

Events/audit:

- record internal reason codes;
- redact user-visible messages.

## Hidden Ref Safety

Hidden refs must be consistently hidden.

Rules:

- hidden refs are not advertised;
- hidden refs do not satisfy fetch access for normal users;
- hidden refs are not included in visible branch resolver output;
- hidden ref names are not included in protocol errors;
- hidden ref updates require internal/admin policy;
- audit sinks redact hidden refs unless internal diagnostics are authorized.

Tests should include objects reachable from both hidden and visible refs. If a
wanted object is reachable from a visible authorized ref, fetch can be allowed
without disclosing the hidden ref.

## Limits as Policy

Some limits are policy decisions:

- maximum upload wants;
- maximum upload haves;
- maximum receive commands;
- maximum pushed pack bytes;
- maximum ref name length;
- maximum push options count;
- maximum branch creation count per push;
- maximum tag creation count per push.

Protocol parsers enforce hard safety limits. Policy can set repository-specific
business limits below those hard limits.

## Events and Audit

Every policy deny should produce structured diagnostics.

Record:

- operation id;
- repository;
- actor;
- operation kind;
- decision reason code;
- ref name when safe;
- hidden/redacted marker;
- matched policy rule id;
- result.

Successful sensitive actions should also be auditable:

- force push;
- delete branch;
- delete tag;
- protected ref update;
- migration ref import;
- mirror force update.

Do not log raw credentials, object contents, or raw pack bytes.

## Migration and Parity

JGit-backed behavior is the compatibility reference for current basic flows, but
native policy should make implicit behavior explicit.

Migration parity checks should cover:

- current branch fetch restrictions;
- ordinary push create/update;
- rejected non-fast-forward;
- force push where currently allowed;
- delete events;
- tag create/update/delete behavior;
- hidden ref advertisement if configured.

Where current JGit behavior is too permissive, native rollout should document the
intentional policy tightening and gate it behind configuration if needed.

## Implementation Phases

Phase 1: Policy model and ref classifier.

Define ref classes, policy request/result objects, reason codes, protected/hidden
ref patterns, and safe defaults.

Phase 2: Fetch policy adapter.

Move current `GitFetchAccessRules` behavior behind native fetch policy while
preserving existing tests and `GitFetchAccessRequest` compatibility.

Phase 3: Visibility filtering.

Apply ref visibility policy to upload-pack and receive-pack advertisements using
current ref snapshots.

Phase 4: Push command policy.

Evaluate receive-pack create/update/delete/tag/force/protected-ref decisions
before ref updates. Produce per-command policy results.

Phase 5: Save-files policy.

Apply internal write policy to native `saveFiles`, including branch create and
protected ref checks.

Phase 6: Hidden ref integration.

Ensure reachability, branch resolver, advertisement, errors, events, and audit
all respect hidden refs.

Phase 7: Protected ref configuration.

Add global/repository policy configuration for protected refs, delete policy,
tag policy, force policy, and limits.

Phase 8: Token/scope integration.

Map application token scopes or future OAuth scopes into Git policy decisions.

Phase 9: Mirror and migration integration.

Apply explicit policy to mirror sync and migration/cutover actors.

Phase 10: Audit and diagnostics.

Emit policy decision records through Git events/logging with redaction.

## Verification

Cover at least these cases:

- production policy code has no JGit dependency;
- repository read grant is required for fetch;
- branch-restricted fetch allows wanted objects reachable from an allowed branch;
- branch-restricted fetch denies wanted objects reachable only from denied branch;
- wanted object reachable only from hidden ref is denied without leaking ref name;
- wanted object reachable from both visible allowed and hidden ref is allowed
  without disclosing hidden ref;
- upload advertisement omits hidden refs;
- receive advertisement omits hidden refs for normal users;
- branch create is allowed only when policy allows it;
- branch fast-forward update is allowed with repository write;
- non-fast-forward update is rejected without force permission;
- force update is allowed only with force policy/grant;
- protected branch update is rejected for normal writer;
- protected branch update is allowed only for configured admin/internal actor;
- branch delete requires delete policy;
- default branch delete is rejected;
- tag create follows tag policy;
- tag update and delete are rejected by default;
- unsupported ref namespace is denied by default;
- `saveFiles` cannot create/update protected refs without internal permission;
- policy limit rejects too many receive commands before ref updates;
- receive-pack maps policy denies to sanitized `ng` reasons;
- upload-pack maps unauthorized wants to sanitized protocol failure;
- policy decision events include reason code and matched rule id;
- hidden ref names are redacted in user-visible logs;
- migration actor can import refs only under explicit migration policy;
- mirror actor follows mirror-specific force/delete policy;
- existing `GitFetchAccessRules` tests pass through compatibility adapter.

## Open Questions

Should branch restrictions apply to tags, or should tag visibility/write policy
be independent from branch grants?

Should a fetch want be allowed when any visible authorized ref reaches it, or
only when every visible ref that reaches it is authorized?

What grant names should represent force push, delete refs, protected ref update,
and tag administration in ACL?

Should protected ref rules live in repository metadata, global configuration, ACL
grants, or a future repository policy file?

Should native policy intentionally tighten current JGit-backed force/delete/tag
behavior during rollout, or preserve exact compatibility first?

How should policy behave when the commit graph cannot resolve a wanted object
within configured traversal limits?
