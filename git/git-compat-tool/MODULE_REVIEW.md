# Module review: git/git-compat-tool

## 1. Standalone export must enforce Git tree ordering across internal-format conversion

- **Problem and trigger.** The internal repository is allowed to depart from Git's format constraints.
  A future standalone exporter must not assume that copying internal object bytes produces a compatible
  repository. Java String ordering and unsigned UTF-8 ordering differ for names U+E000 and U+10000.
  An imported pack may also contain a tree whose entries are already out of order.
- **Sources and owner.** The current [file saver comparator](../git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/NativeRepositoryFileSaver.java#L113)
  keeps newly generated trees ordered for existing Git clients, and
  [Unicode tests with a JGit observer](../git-native-storage/src/test/java/pro/deta/orion/git/nativestorage/NativeGitFileModesTest.java)
  check new saves and imported-tree updates. This does not rewrite arbitrary received packs.
  [Pack ingestion](../git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/pack/PackIngestionOutput.java#L56)
  and [receive publication](../git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/receive/NativeGitReceivePack.java#L21)
  check pack ingestion and object closure, without checking tree ordering. The
  [PackReader verifyTree placeholder](../git-parser/src/main/java/pro/deta/orion/git/parser/v2/pack/PackReader.java)
  reserves future verification and currently changes no behavior; resolved delta trees require resolved content.
  [Reader regression coverage](../git-parser/src/test/java/pro/deta/orion/git/parser/v2/pack/PackReaderTest.java)
  preserves the original bytes and IDs of an unsorted tree.
  This empty module reserves the standalone exporter boundary; it contains no exporter implementation yet.
- **Documented behavior.** The user explicitly assigns conversion from the internal format to a Git-compatible
  repository to a future standalone exporter. Git's [base_name_compare](https://github.com/git/git/blob/master/tree.c#L93)
  compares name bytes and treats directories as names followed by '/'.
  [git fsck](https://git-scm.com/docs/git-fsck#Documentation/git-fsck.txt-treeNotSorted) reports treeNotSorted as ERROR.
  This does not imply that every ordinary clone or checkout necessarily fails.
- **Contract.** Exported trees must satisfy Git ordering while preserving names, modes, contents, and topology.
  Internal-format evolution must not silently become the export format. Unchanged copying of internal object
  bytes is unsafe unless the exporter verifies their compatibility. This note records an explicit future
  exporter requirement and does not claim that arbitrary currently received packs are normalized.
  Fetch must return the existing tree bytes unchanged: their object IDs and graph references are already fixed.
- **Minimal repair.** When implementing the exporter, sort exported entries by unsigned UTF-8 bytes with Git's
  directory suffix rule. If conversion changes a tree ID, rebuild referencing trees and commits and translate
  exported ref targets. Validate root and nested Unicode names, a.c/a/x/a0 ordering, and imported-tree updates
  using an independent Git reader and git fsck.
- **Alternatives and consequences.** The current saver fix does not replace an exporter conversion boundary
  or establish an acceptance policy for received packs. Sorting only the exported root leaves nested trees
  invalid; rewriting tree bytes without remapping their IDs and references corrupts the exported graph.
  Tag and signature handling
  needs a separate exporter contract when rewriting referenced objects.
- **Confidence.** High for the ordering mismatch and required Git export rule. No exporter exists, and no
  exporter runtime or format migration was tested.
- **Priority signals.** Required for Git-compatible export, with a small local ordering rule but graph-wide
  reference remapping when IDs change. It is not an active internal save/read failure.
