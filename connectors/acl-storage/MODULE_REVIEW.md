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

## 3. Local path checks remain vulnerable to concurrent directory replacement

**Problem.** An actor able to modify descendants of the configured ACL root can replace a checked directory
with a symlink before a later filesystem operation. The operation still uses an ordinary path and may follow
that intermediate link outside the root. Static links, including dangling links and the lock file, are rejected;
this does not anchor subsequent I/O to the checked directories.

**Sources.** [`LocalAccessControlStorage.resolvePath`](src/main/java/pro/deta/orion/acl/storage/LocalAccessControlStorage.java)
checks every descendant component before load, save, and opening the lock file. Document and lock opens use
`NOFOLLOW_LINKS`, which protects the final component, not intermediate directories. Temporary-file creation,
attribute handling, and publication also use paths.
[`BootstrapContext.validateDirectConfiguration`](../../core/bootstrap/src/main/java/pro/deta/orion/BootstrapContext.java)
uses the same check and opens the final document without following links.

**Documented behavior.** The queued
[`physical containment`](../../docs/plans/tasks/02_hierarchical-orion-configuration/04_acl-storage-hardening/04_local-path-containment.md)
requires containment through use. The accepted limited repair rejects static descendant links and leaves the
concurrent replacement problem open.

**Contract.** The configured root is trusted and may itself be a link. Descendant links are rejected even when
their target stays inside the root. File locks coordinate participating readers and writers, but do not stop an
external actor from renaming directories or replacing the lock file.

**Minimal repair.** Anchor traversal, reads, lock acquisition, temporary-file creation, and replacement to open
directory handles. Preserve nested-directory creation and per-file atomic publication. Resolve platform support
before selecting a native implementation or changing supported Local filesystem behavior.

**Alternatives and consequences.** Repeated path checks only narrow the race. Requiring trusted, non-mutable
ancestor directories changes the deployment contract. Java's `SecureDirectoryStream` is provider-dependent;
the installed macOS Corretto 21 provider returned an ordinary `UnixDirectoryStream` during inspection.

**Confidence.** High for the check/use gap from code inspection. Static file, directory, dangling, and lock-link
cases are covered by behavior tests. An adversarial concurrent directory-replacement test has not been executed.

**Priority signals.** Importance: high where another actor can mutate descendants while Orion accesses ACLs.
Repair ease: low because platform-specific operations and directory-creation semantics need an explicit design.
