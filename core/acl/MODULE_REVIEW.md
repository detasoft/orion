# Module Review: core/acl

## 1. Authentication can assemble authority from different ACL snapshots

**Problem.** Snapshot A assigns Alice a non-admin operators role. Authentication captures Alice from A;
a reload publishes B, removing her membership and granting operators admin rights for another user.
mergeGrants follows Alice's old membership but resolves the role from B, producing admin authority for Alice
that neither complete snapshot grants. Old credential verification can participate in the same mixed decision.

**Sources.** [SSH user capture](src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java#L549),
[password/enrollment path](src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java#L441),
[bearer path](src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java#L588),
[grant assembly and fresh role/grant reads](src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java#L1232),
[reload publication](src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java#L932),
[SSH identity retention](../../net/git-transport/src/main/java/pro/deta/orion/transport/git/auth/OrionSshAuthenticator.java#L51),
and [ACL tests](src/test/java/pro/deta/orion/acl/OrionAccessControlServiceImplTest.java).
Tests cover mutation serialization, snapshot conflicts and root/token rules, without overlapping principal
grant assembly and reload.

**Documented behavior.** AccessControl is published through an AtomicReference as a complete configuration.
No explicit linearizable-authentication or immediate live-session revocation guarantee was found.

**Contract.** Either a complete old or complete new ACL decision may be acceptable during an overlapping
update; authority absent from both snapshots is not. This does not require revoking existing SSH sessions.

**Minimal repair.** Capture one existing AccessControl value per authentication/grant-resolution operation and
pass it through user, role and grant lookup. Remove repeated accessControl.get() calls from that operation.
Verify a controlled concurrent reload never yields authority absent from both complete snapshots.

**Alternatives and consequences.** Taking reloadLock throughout authentication can enforce consistency but
unnecessarily serializes credential verification with writes. Snapshot passing reuses immutable publication,
adds no persistent state or public API, and leaves live-session revocation policy unchanged.

**Confidence.** High in the mixed-version path; actual incidence depends on reload timing and was not reproduced.

**Priority signals.** Importance: high because concurrent ACL edits can synthesize unintended admin authority.
Repair ease: medium, requiring consistent snapshot propagation across existing authentication helpers.
