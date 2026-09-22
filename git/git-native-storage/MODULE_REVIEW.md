# Module Review: `git/git-native-storage`

## 4. File loading and saving duplicate parsing and path rules

**Problem and evidence.**
[File loader](src/main/java/pro/deta/orion/git/nativestorage/NativeRepositoryFileLoader.java#L98) and
[File saver](src/main/java/pro/deta/orion/git/nativestorage/NativeRepositoryFileSaver.java#L166) separately find
a commit's root tree.
Their tree-entry parsers at lines 119 and 273 repeat mode/name/NUL/ObjectId decoding with separate records;
branch and path normalization are duplicated as well. Load followed by save therefore uses independently
maintained interpretations of the same data.
[ACL storage](../../connectors/acl-storage/src/main/java/pro/deta/orion/acl/storage/NativeGitAccessControlStorage.java#L51)
and
[key-material storage](../../core/bootstrap/src/main/java/pro/deta/orion/NativeGitKeyMaterialContentStore.java#L55)
exercise both paths.

**Contract.** Preserve commit selection, invalid-path rejection, checked error mapping and current name
handling. A missing branch remains an error for loading but a valid absent parent for branch creation.

**Minimal repair and validation.** Reuse package-private parsing and normalization operations from the
existing native file-loading owner in the saver, including a tree-entry value carrying mode, name and ID.
Keep caller-specific missing-branch behavior. Cover nested/non-ASCII paths, truncated tree entries, malformed
commits, missing files, branch creation and expected-version saves through
[NativeGitRepositoryTest](src/test/java/pro/deta/orion/git/nativestorage/NativeGitRepositoryTest.java).

**Alternatives and consequences.** A new public parser service is unnecessary.
Do not mechanically merge this with parser `GitObjectLinks.readTree`: graph traversal skips raw name bytes
without allocation and excludes gitlinks, while file lookup needs decoded names.
A shared decoded-name DTO would change those contracts.

**Confidence and priority.** High from the two implementations and actual consumers.
P2 maintenance duplication; local repair with no storage-format change.

## 5. Flattening existing trees discards untouched file modes

**Problem and evidence.**
[prepareFiles](src/main/java/pro/deta/orion/git/nativestorage/NativeRepositoryFileSaver.java#L99)
reduces the existing tree to `TreeMap<String,ObjectId>`.
[readTreeEntries](src/main/java/pro/deta/orion/git/nativestorage/NativeRepositoryFileSaver.java#L188) drops
every non-directory mode,
then [writeTree](src/main/java/pro/deta/orion/git/nativestorage/NativeRepositoryFileSaver.java#L210) writes each
leaf as `100644`.
Saving an unrelated configuration file therefore changes an untouched executable (`100755`) or symlink
(`120000`) into an ordinary file. A gitlink can become a regular-file entry pointing to a commit.
These repositories can be populated through ordinary Git transport or proxy fetch; no regular-files-only
restriction was found.

**Contract.** Unmodified paths retain their content and mode. Preserve expected-parent construction,
default-branch initialization, independent generated packs and conditional ref publication.

**Minimal repair and validation.** Keep mode and ID in the saver's working entries and use the retained
mode for untouched leaves. New requested files may retain the current regular-file policy.
Extend [NativeGitFileUpdateTest](src/test/java/pro/deta/orion/git/nativestorage/NativeGitFileUpdateTest.java)
and
[NativeGitRepositoryTest](src/test/java/pro/deta/orion/git/nativestorage/NativeGitRepositoryTest.java) with
unrelated updates beside executable files,
symlinks and gitlinks, including nested entries and persistence reopening.

**Alternatives and consequences.** A tree service or complete storage rewrite is unnecessary.
Whether directly editing an existing symlink preserves its mode is a separate API decision; preserving
untouched entries does not depend on it. Retain stale-ref rejection and the validated-pack retention contract.

**Confidence and priority.** High from the loss of mode and unconditional serialization; not reproduced
at runtime. P2 correctness issue with higher priority than the neighboring parsing cleanup.
