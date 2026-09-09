# Module Review: `connectors/acl-storage`

## 1. A resolved repository identity is reparsed into a different identity

**Problem.** A local repository name containing a percent escape can resolve and provision successfully, then make
ACL startup open a different repository. For example, `local:team%2Frepo` is normalized by bootstrap as the literal
repository name `team%2Frepo`. The ACL resolver reconstructs `local:team%2Frepo`, and the connector parses it as a
URI whose scheme-specific part is decoded to `team/repo`. Startup then fails after successful bootstrap, or reads
and writes the wrong repository if both identities exist.

**Sources.** Bootstrap preserves the literal substring in
[`ProxyAwareNativeGitRepositoryProvider.prepareLocal`](../../git/git-native-proxy/src/main/java/pro/deta/orion/git/proxy/ProxyAwareNativeGitRepositoryProvider.java#L196)
and its
[`repositoryName` normalization](../../git/git-native-proxy/src/main/java/pro/deta/orion/git/proxy/ProxyAwareNativeGitRepositoryProvider.java#L335).
The resolved identity is stored by
[`ResolvedBootstrapSource`](../../git/git-native-proxy/src/main/java/pro/deta/orion/git/proxy/ResolvedBootstrapSource.java#L7),
then converted back to a locator by
[`AccessControlStorageResolver.resolve`](src/main/java/pro/deta/orion/acl/storage/AccessControlStorageResolver.java#L17)
and reparsed by
[`NativeGitAccessControlStorage.repositoryName`](src/main/java/pro/deta/orion/acl/storage/NativeGitAccessControlStorage.java#L148).
[`ResourceLocation.normalizedRelativePath`](../../core/schema/src/main/java/pro/deta/orion/util/ResourceLocation.java#L74)
uses decoded URI accessors. The existing
[`resolverProjectsRepositoryBackedSourceToLocalAlias`](src/test/java/pro/deta/orion/acl/storage/NativeGitAccessControlStorageTest.java#L39)
test covers an ordinary alias but not an encoded identity.

**Documented behavior.** The bootstrap proxy plan requires ACL to consume the resolved configuration handle and
the exact provider instance in
[`Make ACL consume only the resolved configuration source`](../../docs/plans/2026-09-02-bootstrap-proxy-runtime-implementation.md#L151).
The queued
[`canonical repository names`](../../docs/plans/upcoming-work/01_acl-storage-hardening/03_canonical-repository-names.md)
task separately requires one repository identity at every raw ingress.

**Contract.** `ResolvedBootstrapSource.repositoryName()` is the identity already selected by the provider. ACL
reads and writes must use that value verbatim, including for proxy aliases. Raw-locator decoding belongs before
resolution; a later connector may not reinterpret the resolved identity. This is an internal cross-module identity
guarantee, not a demonstrated filesystem escape: the file provider hashes the exact repository name.

**Minimal repair.** Construct native ACL storage directly from the resolved repository name, ref, paths, and
creation flag. Delete the name-to-URI round trip and the connector's second repository-name parser. Update direct
constructor tests to use the production resolved-source path; do not add a compatibility constructor or another
identity type. Preserve provider-mediated reads and writes.

**Alternatives and consequences.** The broader canonical-name task can replace every raw ingress parser, but it
affects more modules and needs a compatibility decision for existing persisted names. Rejecting percent escapes
before provisioning contains this trigger but removes currently accepted names. Adjusting only the second parser
retains two owners that can diverge again. Directly consuming the resolved identity changes only an internal
construction path and does not rewrite Git data or external locator syntax.

**Confidence.** High. The mismatch follows the complete production path and standard decoded `URI` accessors;
the trigger has not yet been exercised by a runtime regression test.

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

## 4. Two helpers remain after their storage path was removed

**Problem.** The module still compiles the unused production helper `AccessControlStorageSecret` and an unused
module-local copy of `PlainRootTokenAccessForTests`. Remote bootstrap secret resolution now belongs to the proxy
runtime, while other modules own separate test accessors that are actually referenced.

**Sources.** The unused files are
[`AccessControlStorageSecret`](src/main/java/pro/deta/orion/acl/storage/AccessControlStorageSecret.java#L11) and
[`PlainRootTokenAccessForTests`](src/test/java/pro/deta/orion/auth/PlainRootTokenAccessForTests.java#L3).
Live bootstrap secret ownership enters through
[`ProxyAwareNativeGitRepositoryProvider`](../../git/git-native-proxy/src/main/java/pro/deta/orion/git/proxy/ProxyAwareNativeGitRepositoryProvider.java#L50).
The module's [`pom.xml`](pom.xml#L1) does not publish a test JAR or register either class as a service.

**Documented behavior.** The existing
[`unused helper deletion`](../../docs/plans/upcoming-work/01_acl-storage-hardening/02_remove-unused-helpers.md)
task names these two files and requires preservation of independently used copies.

**Contract.** No production, wire, persisted, reflective, service-loader, generated-wiring, or published test
contract uses either file. Deletion must remain limited to this module's copies.

**Minimal repair.** Delete the two files without replacement and remove only dependencies or package exposure
made unused by those deletions.

**Alternatives and consequences.** Retention preserves no verified behavior. Centralizing every similarly named
test accessor would create a wider fixture contract with no production requirement. Deletion has no supported
runtime consequence.

**Confidence.** High from repository-wide symbol, service, build, and history searches. External source consumers
of this unpublished module-local test class are not supported by the Maven graph.
