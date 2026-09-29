# Module review: connectors/acl-storage

## 3. Local path checks leave a directory-swap TOCTOU gap

**Problem.** An actor able to rename descendant directories can replace a checked directory with a symlink
before a read, create, publication, or cleanup operation. Final-component `NOFOLLOW_LINKS` does not protect
intermediate components, so operations can escape the ACL root. Atomic generation publication does not anchor
filesystem operations against external directory replacement.

**Sources.** [LocalAccessControlStorage](src/main/java/pro/deta/orion/acl/storage/LocalAccessControlStorage.java):
`resolvePath`, `readDocument`, `publishSnapshot`, and `deleteGeneration` operate on checked paths rather than
retained directory handles. [BootstrapContext](../../core/bootstrap/src/main/java/pro/deta/orion/BootstrapContext.java)
uses the same storage reader. [Existing tests](src/test/java/pro/deta/orion/acl/storage/LocalAccessControlStorageTest.java)
reject static descendant symlinks but do not exercise concurrent directory replacement.

**Documented behavior.** The [containment plan](../../docs/plans/tasks/02_hierarchical-orion-configuration/04_acl-storage-hardening/04_local-path-containment.md)
requires anchoring through use, safe creation, and no outside-root access.

**Contract.** The root is trusted and may itself be a symlink. Descendant symlinks are rejected even when their
target remains inside the root. The cooperative configuration lock does not exclude external directory renames.
Preserve complete-generation publication and nested configured paths.

**Minimal repair.** Choose a supported directory-handle mechanism for traversal, reads, creation, pointer
publication, and cleanup. Establish supported providers and test adversarial replacement during these operations.

**Alternatives and consequences.** Rechecking paths narrows the race but does not close it. Requiring immutable
ancestors changes the deployment contract. `SecureDirectoryStream` availability depends on the filesystem provider.

**Confidence.** High in the path-based gap; adversarial races and provider capabilities have not been tested.

**Priority signals.** Importance: high when another actor can rename descendant directories. Repair ease: low
because provider support and safe directory creation must be resolved together.
