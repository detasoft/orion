package pro.deta.orion.git.projection.parity;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.common.GitOperationException;
import pro.deta.orion.git.common.GitRepository;
import pro.deta.orion.git.common.GitRepositoryFileSnapshot;
import pro.deta.orion.git.jgit.JGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryAdapter;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class AbstractRepositoryIntegrationParityTest {
    private static final String NULL_ID = "0".repeat(40);
    private static final PersonIdent AUTHOR =
            new PersonIdent("Parity", "parity@example.test", 0L, 0);

    @TempDir
    Path tempDir;

    @Test
    void singleFileLoadCapabilityMatches() throws Exception {
        Scenario scenario = new Scenario(
                "single file load capability",
                "Load one regular file from a short branch name through both backends.",
                "Both backends return the same normalized path key, exact file bytes, and commit version.");

        assertLoadFilesMatches(scenario, "main", List.of("README.md"));
    }

    @Test
    void executableFileLoadCapabilityMatches() throws Exception {
        Scenario scenario = new Scenario(
                "executable file load capability",
                "Load one executable file path through both backends.",
                "Executable mode is a storage detail here; externally both backends return the same bytes.");

        assertLoadFilesMatches(scenario, "main", List.of("src/run.sh"));
    }

    @Test
    void directoryPathLoadCapabilityMatchesCurrentJGitBehavior() throws Exception {
        Scenario scenario = new Scenario(
                "directory path load capability",
                "Load a directory path through loadFiles to capture current JGit behavior.",
                "Native returns the same tree object bytes and version that JGit exposes today.");

        assertLoadFilesMatches(scenario, "main", List.of("src"));
    }

    @Test
    void fullRefResolutionCapabilityMatches() throws Exception {
        Scenario scenario = new Scenario(
                "full ref resolution capability",
                "Load one file using a full refs/heads/* name instead of a short branch.",
                "Both backends resolve the full ref directly and return the same snapshot.");

        assertLoadFilesMatches(scenario, "refs/heads/feature", List.of("docs/spec.txt"));
    }

    @Test
    void missingBranchFailureCapabilityMatches() throws Exception {
        Scenario scenario = new Scenario(
                "missing branch failure capability",
                "Load one file from a branch that does not exist.",
                "Both backends fail with the same exception type and message.");

        assertFailureMatches(scenario, "missing", List.of("README.md"));
    }

    @Test
    void missingPathFailureCapabilityMatches() throws Exception {
        Scenario scenario = new Scenario(
                "missing path failure capability",
                "Load a top-level path that does not exist on an existing branch.",
                "Both backends fail with the same exception type and message.");

        assertFailureMatches(scenario, "main", List.of("missing.txt"));
    }

    @Test
    void loadFilesCombinesRefCommitTreeAndObjectCapabilities() throws Exception {
        Scenario scenario = new Scenario(
                "loadFiles combines ref, commit, tree, and object capabilities",
                "Load regular, executable, directory, and feature-branch paths from deterministic refs.",
                "The combined external snapshot is identical: keys, object bytes, and commit versions match.");

        try (RepositoryFixture fixture = RepositoryFixture.create(tempDirectory(scenario))) {
            assertLoadFilesMatches(fixture, scenario, "main", List.of(
                    "README.md",
                    "src/run.sh",
                    "src"));
            assertLoadFilesMatches(fixture, scenario, "refs/heads/feature", List.of("docs/spec.txt"));
        }
    }

    @Disabled("TODO: compare independent ref listing capability: create identical visible refs, list refs "
            + "through both backends, and expect identical ref names and target ids.")
    @Test
    void refListingCapabilityMatches() {
    }

    @Disabled("TODO: compare independent commit metadata capability: resolve the same commits through both "
            + "backends and expect identical ids, parent order, root tree ids, authors, and messages.")
    @Test
    void commitMetadataCapabilityMatches() {
    }

    @Disabled("TODO: compare independent tree listing capability: list the same directory through both "
            + "backends and expect identical names, object ids, file modes, and entry types.")
    @Test
    void treeListingCapabilityMatches() {
    }

    @Disabled("TODO: compare independent saveFiles capability once native saveFiles exists: apply the same "
            + "file update and expect the same externally readable files and compatible commit metadata.")
    @Test
    void saveFilesCapabilityMatches() {
    }

    @Disabled("TODO: compare independent receive-pack capability with one client repo and two remotes "
            + "(jgit/native): push the same update to each and expect identical accepted refs/state.")
    @Test
    void receivePackCapabilityMatches() {
    }

    @Disabled("TODO: compare independent upload-pack capability with one client repo and two remotes "
            + "(jgit/native): fetch the same ref from each and expect identical fetched refs/objects.")
    @Test
    void uploadPackCapabilityMatches() {
    }

    @Disabled("TODO: compare save-then-load dependent capability combination: save identical files, then "
            + "load them back and expect the same file snapshots and version behavior.")
    @Test
    void saveThenLoadCombinationMatches() {
    }

    @Disabled("TODO: compare receive-then-fetch combination with one client repo and two remotes "
            + "(jgit/native): push then fetch back and expect identical client-visible state.")
    @Test
    void receiveThenFetchCombinationMatches() {
    }

    @Disabled("TODO: compare projection-accelerated read as an implementation variant only through the "
            + "loadFiles result, with canonical fallback producing the same external snapshot.")
    @Test
    void projectionAcceleratedLoadFilesCombinationMatches() {
    }

    private void assertLoadFilesMatches(
            Scenario scenario,
            String branch,
            List<String> paths) throws Exception {
        try (RepositoryFixture fixture = RepositoryFixture.create(tempDirectory(scenario))) {
            assertLoadFilesMatches(fixture, scenario, branch, paths);
        }
    }

    private void assertLoadFilesMatches(
            RepositoryFixture fixture,
            Scenario scenario,
            String branch,
            List<String> paths) {
        SnapshotResult jgit = loadFromJGit(fixture, branch, paths);
        SnapshotResult nativeResult = loadFromNative(fixture, branch, paths);

        assertThat(nativeResult.toBytes())
                .as(scenario.description())
                .isEqualTo(jgit.toBytes());
    }

    private void assertFailureMatches(
            Scenario scenario,
            String branch,
            List<String> paths) throws Exception {
        try (RepositoryFixture fixture = RepositoryFixture.create(tempDirectory(scenario))) {
            SnapshotResult jgit = loadFromJGit(fixture, branch, paths);
            SnapshotResult nativeResult = loadFromNative(fixture, branch, paths);

            assertThat(nativeResult.toBytes())
                    .as(scenario.description())
                    .isEqualTo(jgit.toBytes());
        }
    }

    private Path tempDirectory(Scenario scenario) throws IOException {
        return Files.createTempDirectory(tempDir, scenario.fileName() + "-");
    }

    private static SnapshotResult loadFromJGit(
            RepositoryFixture fixture,
            String branch,
            List<String> paths) {
        try {
            return SnapshotResult.success(fixture.jgit().loadFiles(branch, paths));
        } catch (Exception error) {
            return SnapshotResult.failure(error);
        }
    }

    private static SnapshotResult loadFromNative(
            RepositoryFixture fixture,
            String branch,
            List<String> paths) {
        try {
            return SnapshotResult.success(fixture.nativeRepository().loadFiles(branch, paths));
        } catch (Exception error) {
            return SnapshotResult.failure(error);
        }
    }

    private record RepositoryFixture(
            GitRepository jgit,
            GitRepository nativeRepository) implements AutoCloseable {

        private static RepositoryFixture create(Path tempDir) throws Exception {
            Files.createDirectories(tempDir);
            Repository repository = FileRepositoryBuilder.create(
                    tempDir.resolve("jgit.git").toFile());
            repository.create(true);
            try {
                FixtureIds ids = writeFixtureObjects(repository);
                updateRef(repository, "refs/heads/main", ids.mainCommit());
                updateRef(repository, "refs/heads/feature", ids.featureCommit());
                NativeGitRepository nativeRepository = mirrorToNative(repository, ids);
                return new RepositoryFixture(
                        new JGitRepository("projection-parity.git", repository),
                        new NativeGitRepositoryAdapter(nativeRepository));
            } catch (Exception error) {
                repository.close();
                throw error;
            }
        }

        @Override
        public void close() {
            jgit.close();
        }
    }

    private static FixtureIds writeFixtureObjects(Repository repository) throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            ObjectId readmeBase = inserter.insert(Constants.OBJ_BLOB, bytes("base readme\n"));
            ObjectId readmeMain = inserter.insert(Constants.OBJ_BLOB, bytes("main readme\n"));
            ObjectId specBase = inserter.insert(Constants.OBJ_BLOB, bytes("spec v1\n"));
            ObjectId specFeature = inserter.insert(Constants.OBJ_BLOB, bytes("spec v2\n"));
            ObjectId runScript = inserter.insert(Constants.OBJ_BLOB, bytes("#!/bin/sh\necho parity\n"));

            ObjectId baseTree = tree(inserter, List.of(
                    entry("README.md", FileMode.REGULAR_FILE, readmeBase),
                    entry("docs/spec.txt", FileMode.REGULAR_FILE, specBase),
                    entry("src/run.sh", FileMode.EXECUTABLE_FILE, runScript)));
            ObjectId baseCommit = commit(inserter, baseTree, List.of(), "base\n");

            ObjectId mainTree = tree(inserter, List.of(
                    entry("README.md", FileMode.REGULAR_FILE, readmeMain),
                    entry("docs/spec.txt", FileMode.REGULAR_FILE, specBase),
                    entry("src/run.sh", FileMode.EXECUTABLE_FILE, runScript)));
            ObjectId mainCommit = commit(inserter, mainTree, List.of(baseCommit), "main\n");

            ObjectId featureTree = tree(inserter, List.of(
                    entry("README.md", FileMode.REGULAR_FILE, readmeBase),
                    entry("docs/spec.txt", FileMode.REGULAR_FILE, specFeature),
                    entry("src/run.sh", FileMode.EXECUTABLE_FILE, runScript)));
            ObjectId featureCommit = commit(
                    inserter,
                    featureTree,
                    List.of(baseCommit),
                    "feature\n");
            inserter.flush();
            return new FixtureIds(
                    List.of(
                            readmeBase,
                            readmeMain,
                            specBase,
                            specFeature,
                            runScript,
                            baseTree,
                            mainTree,
                            featureTree,
                            baseCommit,
                            mainCommit,
                            featureCommit),
                    mainCommit,
                    featureCommit);
        }
    }

    private static TreeEntry entry(String path, FileMode mode, ObjectId objectId) {
        return new TreeEntry(path, mode, objectId);
    }

    private static ObjectId tree(ObjectInserter inserter, List<TreeEntry> entries)
            throws IOException {
        DirCache cache = DirCache.newInCore();
        DirCacheBuilder builder = cache.builder();
        List<TreeEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(TreeEntry::path));
        for (TreeEntry treeEntry : sorted) {
            DirCacheEntry entry = new DirCacheEntry(treeEntry.path());
            entry.setFileMode(treeEntry.mode());
            entry.setObjectId(treeEntry.objectId());
            builder.add(entry);
        }
        builder.finish();
        return cache.writeTree(inserter);
    }

    private static ObjectId commit(
            ObjectInserter inserter,
            ObjectId tree,
            List<ObjectId> parents,
            String message) throws IOException {
        CommitBuilder builder = new CommitBuilder();
        builder.setTreeId(tree);
        builder.setParentIds(parents);
        builder.setAuthor(AUTHOR);
        builder.setCommitter(new PersonIdent(AUTHOR, AUTHOR.getWhen(), TimeZone.getTimeZone("UTC")));
        builder.setMessage(message);
        return inserter.insert(builder);
    }

    private static void updateRef(Repository repository, String refName, ObjectId newId)
            throws IOException {
        RefUpdate update = repository.updateRef(refName);
        update.setExpectedOldObjectId(ObjectId.zeroId());
        update.setNewObjectId(newId);
        RefUpdate.Result result = update.update();
        if (result != RefUpdate.Result.NEW) {
            throw new IllegalStateException("Failed to update " + refName + ": " + result);
        }
    }

    private static NativeGitRepository mirrorToNative(Repository repository, FixtureIds ids)
            throws IOException, GitOperationException {
        NativeGitRepository nativeRepository = new NativeGitRepository(
                "projection-parity.git",
                new LooseRefStore(),
                new LooseObjectStore(),
                "refs/heads/main");
        try (ObjectReader reader = repository.newObjectReader()) {
            LinkedHashSet<ObjectId> pending = new LinkedHashSet<>(ids.objects());
            LinkedHashSet<ObjectId> mirrored = new LinkedHashSet<>();
            while (!pending.isEmpty()) {
                ObjectId objectId = removeFirst(pending);
                if (!mirrored.add(objectId)) {
                    continue;
                }
                ObjectLoader loader = reader.open(objectId);
                byte[] content = loader.getBytes();
                GitObjectId nativeId = nativeRepository.writeObject(nativeType(loader.getType()), content);
                if (!Objects.equals(nativeId.value(), objectId.name())) {
                    throw new IllegalStateException("Mirrored object id changed: " + objectId.name());
                }
                addReferencedObjects(pending, loader.getType(), content);
            }
        }
        nativeRepository.updateRef("refs/heads/main", NULL_ID, ids.mainCommit().name());
        nativeRepository.updateRef("refs/heads/feature", NULL_ID, ids.featureCommit().name());
        return nativeRepository;
    }

    private static ObjectId removeFirst(LinkedHashSet<ObjectId> pending) {
        ObjectId first = pending.iterator().next();
        pending.remove(first);
        return first;
    }

    private static void addReferencedObjects(
            LinkedHashSet<ObjectId> pending,
            int objectType,
            byte[] content) {
        switch (objectType) {
            case Constants.OBJ_COMMIT -> addCommitReferences(pending, content);
            case Constants.OBJ_TREE -> addTreeReferences(pending, content);
            case Constants.OBJ_BLOB -> {
            }
            default -> throw new IllegalArgumentException("Unknown JGit object type: " + objectType);
        }
    }

    private static void addCommitReferences(
            LinkedHashSet<ObjectId> pending,
            byte[] content) {
        int offset = 0;
        while (offset < content.length) {
            int lineEnd = lineEnd(content, offset);
            if (lineEnd == offset) {
                return;
            }
            String line = new String(
                    content,
                    offset,
                    lineEnd - offset,
                    StandardCharsets.US_ASCII);
            if (line.startsWith("tree ")) {
                pending.add(ObjectId.fromString(line.substring("tree ".length())));
            } else if (line.startsWith("parent ")) {
                pending.add(ObjectId.fromString(line.substring("parent ".length())));
            }
            offset = lineEnd + 1;
        }
    }

    private static void addTreeReferences(
            LinkedHashSet<ObjectId> pending,
            byte[] content) {
        int offset = 0;
        while (offset < content.length) {
            while (offset < content.length && content[offset] != ' ') {
                offset++;
            }
            offset++;
            while (offset < content.length && content[offset] != 0) {
                offset++;
            }
            offset++;
            if (offset + 20 > content.length) {
                throw new IllegalStateException("Malformed fixture tree object");
            }
            byte[] rawObjectId = new byte[20];
            System.arraycopy(content, offset, rawObjectId, 0, rawObjectId.length);
            pending.add(ObjectId.fromString(HexFormat.of().formatHex(rawObjectId)));
            offset += 20;
        }
    }

    private static int lineEnd(byte[] content, int offset) {
        int index = offset;
        while (index < content.length && content[index] != '\n') {
            index++;
        }
        return index;
    }

    private static ObjectType nativeType(int type) {
        return switch (type) {
            case Constants.OBJ_COMMIT -> ObjectType.COMMIT;
            case Constants.OBJ_TREE -> ObjectType.TREE;
            case Constants.OBJ_BLOB -> ObjectType.BLOB;
            default -> throw new IllegalArgumentException("Unknown JGit object type: " + type);
        };
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record SnapshotResult(
            Optional<GitRepositoryFileSnapshot> snapshot,
            Optional<String> failureType,
            Optional<String> failureMessage) {

        private static SnapshotResult success(GitRepositoryFileSnapshot snapshot) {
            return new SnapshotResult(Optional.of(snapshot), Optional.empty(), Optional.empty());
        }

        private static SnapshotResult failure(Exception error) {
            return new SnapshotResult(
                    Optional.empty(),
                    Optional.of(error.getClass().getName()),
                    Optional.ofNullable(error.getMessage()));
        }

        private byte[] toBytes() {
            StringBuilder output = new StringBuilder();
            if (snapshot.isPresent()) {
                appendSnapshot(output, snapshot.get());
            } else {
                output.append("FAILURE\t")
                        .append(failureType.orElse(""))
                        .append('\t')
                        .append(hex(failureMessage.orElse("")))
                        .append('\n');
            }
            return output.toString().getBytes(StandardCharsets.UTF_8);
        }

        private static void appendSnapshot(
                StringBuilder output,
                GitRepositoryFileSnapshot snapshot) {
            output.append("SNAPSHOT\t")
                    .append(snapshot.version().orElse(""))
                    .append('\n');
            Map<String, byte[]> files = new LinkedHashMap<>(snapshot.files());
            List<String> paths = new ArrayList<>(files.keySet());
            paths.sort(String::compareTo);
            for (String path : paths) {
                output.append("FILE\t")
                        .append(hex(path))
                        .append('\t')
                        .append(hex(files.get(path)))
                        .append('\n');
            }
        }
    }

    private static String hex(String value) {
        return hex(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] value) {
        return HexFormat.of().formatHex(value);
    }

    private record Scenario(
            String name,
            String checks,
            String expected) {

        private Scenario {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(checks, "checks");
            Objects.requireNonNull(expected, "expected");
        }

        private String description() {
            return "Scenario: " + name + "\nChecks: " + checks + "\nExpected: " + expected;
        }

        private String fileName() {
            StringBuilder result = new StringBuilder();
            for (int index = 0; index < name.length(); index++) {
                char character = name.charAt(index);
                if (character >= 'a' && character <= 'z'
                        || character >= 'A' && character <= 'Z'
                        || character >= '0' && character <= '9') {
                    result.append(Character.toLowerCase(character));
                } else if (result.isEmpty()
                        || result.charAt(result.length() - 1) != '-') {
                    result.append('-');
                }
            }
            if (!result.isEmpty() && result.charAt(result.length() - 1) == '-') {
                result.setLength(result.length() - 1);
            }
            return result.toString();
        }
    }

    private record FixtureIds(
            List<ObjectId> objects,
            ObjectId mainCommit,
            ObjectId featureCommit) {

        private FixtureIds {
            objects = List.copyOf(new LinkedHashSet<>(objects));
        }
    }

    private record TreeEntry(
            String path,
            FileMode mode,
            ObjectId objectId) {
    }
}
