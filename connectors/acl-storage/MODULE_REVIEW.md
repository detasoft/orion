# Module Review: `connectors/acl-storage`

## 2. Local save can persist a credential update that reports failure

**Problem.** A credential update passes every loaded ACL document to storage even when only one document changed.
Local storage rewrites every supplied file in configured order. If the changed primary file is writable and a
later unchanged secondary file is not, the primary update is persisted before the save throws. The service returns
`PERSISTENCE_FAILED` without reloading, leaving new durable credentials beside the previous live ACL. A direct
`Files.write` can also truncate the active document before replacement content is fully published.

**Sources.** [`LocalAccessControlStorage.save`](src/main/java/pro/deta/orion/acl/storage/LocalAccessControlStorage.java#L40)
writes entries sequentially. The service copies the full loaded map in
[`saveCredentialDraft`](../../core/acl/src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java#L1161),
saves before strict reload in
[`saveAccessControlSnapshotAndReload`](../../core/acl/src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java#L1537),
and translates the exception to `PERSISTENCE_FAILED` in
[`addSshCredentials`](../../core/acl/src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java#L281).
The split-file service test
[`atomicallyAddsCanonicalKeysAndPreservesTheOtherAclFile`](../../core/acl/src/test/java/pro/deta/orion/acl/OrionAccessControlServiceImplTest.java#L153)
uses an in-memory store and cannot expose filesystem publication failure.

**Documented behavior.** The SSH credential plan requires changing only the owning draft, preserving every other
file, and activating only after successful persistence in
[`snapshot-aware query and mutation helpers`](../../docs/plans/2026-09-04-ssh-credential-commands.md#L260).
The queued
[`saved snapshot contract`](../../docs/plans/upcoming-work/01_acl-storage-hardening/05_exact-snapshot-save.md)
requires an explicit Local publication guarantee but leaves multi-file atomicity as a decision.

**Contract.** A normal single-document mutation must not require write access to byte-identical documents, must
not report failure after publishing that mutation, and must not destroy the previous complete document while
preparing its replacement. Atomicity across genuinely changed documents, power-loss durability, and external-writer
CAS are separate guarantees that the present interface does not define.

**Minimal repair.** Validate every supplied path before mutation, omit byte-identical documents, write each changed
document to a sibling temporary file, and atomically replace the target. Coordinate the replacement with physical
containment. Cover an unchanged non-writable secondary, preparation failure, durable contents, live activation,
and restart.

**Alternatives and consequences.** Selecting changed documents only in the service avoids redundant writes but
does not protect other callers or prevent truncation. Immutable filesystem generations can make genuine
multi-document operations atomic but change the operator-visible layout. Restricting those operations to native
Git removes supported Local capability. Per-file atomic replacement must not be described as a transaction for
[`resetRootPassword`](../../core/acl/src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java#L751),
which can modify multiple documents.

**Confidence.** High for the write order and service outcome. Filesystem fault injection and crash recovery were
not executed during this static review.

**Priority signals.** Importance: high, because a credential mutation can be durable while being reported as
failed and remaining inactive in memory. Repair ease: low, because safe publication must be coordinated with
containment, multiple-document semantics, restart behavior, and filesystem-failure tests.

## 3. Physical Local containment is bypassed through symlinks

**Problem.** A configured path such as `config/orion.xml` passes the lexical check when `config` is a symlink to
an outside directory. Load then reads outside content and save can overwrite it. A symlink at the final file has
the same effect. Normal bootstrap preflight does not close the path: it checks final existence without following
links, then uses regular-file and read operations that follow links, and it does not anchor later storage I/O.

**Sources.** Local storage performs a lexical prefix check in
[`aclPath`](src/main/java/pro/deta/orion/acl/storage/LocalAccessControlStorage.java#L65),
then follows the resolved path during
[`load`](src/main/java/pro/deta/orion/acl/storage/LocalAccessControlStorage.java#L21) and
[`save`](src/main/java/pro/deta/orion/acl/storage/LocalAccessControlStorage.java#L40).
Bootstrap's
[`validateDirectConfiguration`](../../core/bootstrap/src/main/java/pro/deta/orion/BootstrapContext.java#L129)
combines `NOFOLLOW_LINKS` existence with following operations. The current
[`filesystem runtime test`](../../core/bootstrap/src/test/java/pro/deta/orion/component/OrionRuntimeModuleTest.java#L55)
covers ordinary files only.

**Documented behavior.** The queued
[`physical containment`](../../docs/plans/upcoming-work/01_acl-storage-hardening/04_local-path-containment.md)
task explicitly requires protection against intermediate and final symlinks and containment through use.

**Contract.** The configured root is trusted, but control of a descendant link must not grant Orion's process
access to a target outside that root. The attack requires a manipulable link and an outside target readable or
writable by Orion. The boundary applies during bootstrap preflight and every later storage operation.

**Minimal repair.** Resolve the root once, anchor traversal and I/O to it, and reject descendant symlink
components and targets with operations that maintain containment through use. Apply the same boundary to the
bootstrap direct-source read. Preserve safe creation of missing nested directories without introducing a general
storage framework.

**Alternatives and consequences.** A single `toRealPath()` check rejects static escapes but retains a replacement
race. Trusting links weakens the documented security boundary. Rejecting every descendant link is simpler than
supporting safe links but removes linked ACL layouts that currently happen to work. Platform support should be
validated together with atomic replacement.

**Confidence.** High for the static symlink bypass. No adversarial replacement race was executed.

**Priority signals.** Importance: high, because descendant links cross the configured filesystem trust boundary
for both reads and writes. Repair ease: low, because the repair must preserve containment through use across
bootstrap and storage I/O, account for replacement races, and validate platform behavior.
