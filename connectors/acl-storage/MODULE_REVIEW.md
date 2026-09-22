# Module Review: `connectors/acl-storage`

## 2. Local save can partially publish a multi-document change

**Problem.** If several ACL documents actually change, a failure replacing a later document can leave earlier
replacements persisted while the service returns `PERSISTENCE_FAILED` without activating the new snapshot.
Each changed document is replaced atomically, but the sequence of replacements is not a transaction.

**Sources.** [`LocalAccessControlStorage.save`](src/main/java/pro/deta/orion/acl/storage/LocalAccessControlStorage.java)
checks the snapshot version under a file lock, omits byte-identical files, then replaces changed files sequentially.
[`saveAccessControlSnapshotAndReload`](../../core/acl/src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java)
saves before reloading. `resetRootPassword` in the same service can change more than one document.
[`LocalAccessControlStorageTest`](src/test/java/pro/deta/orion/acl/storage/LocalAccessControlStorageTest.java)
covers complete per-file replacement, access attributes, and preparation failure, not multi-file rollback.

**Documented behavior.** The queued
[`saved snapshot contract`](../../docs/plans/tasks/02_hierarchical-orion-configuration/04_acl-storage-hardening/05_exact-snapshot-save.md)
requires an explicit Local publication guarantee but leaves multi-file atomicity as a decision.

**Contract.** Local readers and writers hold the same file lock across the complete operation; the version check
rejects stale saves. Per-file atomic replacement preserves complete documents. These mechanisms prevent a
participating reader from observing an in-progress save, but do not make multi-document publication atomic or
constrain an external editor that ignores the lock. Multi-document failure semantics and power-loss durability
need explicit guarantees.

**Minimal repair.** Decide whether genuinely multi-document mutations require all-or-nothing publication or
an explicit partial-publication result with reconciliation of the live snapshot. Cover failure after the first
replacement, live state, and restart under the chosen contract.

**Alternatives and consequences.** Preparing every temporary file before publishing reduces preparation-related
partial updates but cannot prevent a later rename failure. Immutable generations can make multi-document
publication atomic but change the operator-visible layout. Restricting writes to native Git removes supported
Local capability. Best-effort rollback can itself fail and must not be described as atomic.

**Confidence.** High for sequential publication and the service outcome. Multi-document publication fault
injection and crash recovery have not been executed.

**Priority signals.** Importance: high for multi-document credential operations because durable and live ACLs can
diverge. Repair ease: low because the publication contract and operator-visible layout require a decision.

## 3. Physical Local containment is bypassed through symlinks

**Problem.** A configured path such as `config/orion.xml` passes the lexical check when `config` is a symlink to
an outside directory. Load then reads outside content and save can overwrite it. A symlink at the final file has
the same effect. Normal bootstrap preflight does not close the path: it checks final existence without following
links, then uses regular-file and read operations that follow links, and it does not anchor later storage I/O.

**Sources.** Local storage performs a lexical prefix check in
[`aclPath`](src/main/java/pro/deta/orion/acl/storage/LocalAccessControlStorage.java#L65),
then follows configured links during
[`load`](src/main/java/pro/deta/orion/acl/storage/LocalAccessControlStorage.java#L21) and
[`save`](src/main/java/pro/deta/orion/acl/storage/LocalAccessControlStorage.java#L40).
Bootstrap's
[`validateDirectConfiguration`](../../core/bootstrap/src/main/java/pro/deta/orion/BootstrapContext.java#L129)
combines `NOFOLLOW_LINKS` existence with following operations. The current
[`filesystem runtime test`](../../core/bootstrap/src/test/java/pro/deta/orion/component/OrionRuntimeModuleTest.java#L55)
covers ordinary files only.

**Documented behavior.** The queued
[`physical containment`](../../docs/plans/tasks/02_hierarchical-orion-configuration/04_acl-storage-hardening/04_local-path-containment.md)
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
