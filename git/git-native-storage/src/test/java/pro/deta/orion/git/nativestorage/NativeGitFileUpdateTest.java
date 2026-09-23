package pro.deta.orion.git.nativestorage;

import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.id.PackId;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeGitFileUpdateTest {
    @TempDir
    private Path directory;

    @Test
    void fileSavePublishesAReadablePack() throws Exception {
        NativeGitRepository repository = new FileNativeGitRepositoryProvider(directory)
                .create("demo").valueOrFailure("repository");

        repository.saveFiles("main", files("first"), "first", GitCommitAuthor.EMPTY);

        assertThat(repository.storage().packIds()).hasSize(1);
        PackId packId = repository.storage().packIds().iterator().next();
        assertThat(repository.storage().readPack(packId, (size, input) -> input.readBytes(4)))
                .hasValueSatisfying(header -> assertThat(header).isEqualTo("PACK".getBytes(StandardCharsets.US_ASCII)));
        NativeGitRepository reopened = new FileNativeGitRepositoryProvider(directory)
                .find("demo").valueOrFailure("repository");
        assertThat(reopened.loadFiles("main", List.of("config.txt")).files())
                .containsAllEntriesOf(files("first"));
    }

    @Test
    void staleFileSaveRetainsValidatedPackWithoutChangingTheBranch() throws Exception {
        NativeGitRepository repository = new FileNativeGitRepositoryProvider(directory)
                .create("demo").valueOrFailure("repository");
        repository.saveFiles("main", files("first"), "first", GitCommitAuthor.EMPTY);
        String expected = repository.refs().get("refs/heads/main");
        repository.saveFiles("main", files("second"), "second", GitCommitAuthor.EMPTY);
        String current = repository.refs().get("refs/heads/main");

        NativeGitFileUpdate update = repository.prepareFileUpdate(
                "main", expected, files("stale"), "stale", GitCommitAuthor.EMPTY);
        List<RefUpdateResult> results = repository.publishPack(
                update.pack(), update.refUpdates(), true, GitNativeRepositoryAccessHook.ALLOW_ALL);

        assertThat(results).extracting(RefUpdateResult::status)
                .containsExactly(RefUpdateResult.Status.EXPECTED_OLD_MISMATCH);
        assertThatThrownBy(() -> GitOperationException.requireSuccess(results))
                .isInstanceOf(GitRepositoryConcurrentUpdateException.class);

        assertThat(repository.refs()).containsEntry("refs/heads/main", current);
        assertThat(repository.loadFiles("main", List.of("config.txt")).files())
                .containsAllEntriesOf(files("second"));
        assertThat(repository.storage().packIds()).hasSize(3);
        assertThat(repository.readObject(update.refUpdates().getFirst().newId().orElseThrow())).isPresent();
    }

    @Test
    void preparedPackIsIndependentOfItsReadersAndUnderstoodByJGit() throws Exception {
        NativeGitRepository repository = new InMemoryNativeGitRepositoryProvider()
                .create("demo").valueOrFailure("repository");
        NativeGitFileUpdate update = repository.prepareFileUpdate(
                "main", files("prepared"), "prepared", GitCommitAuthor.EMPTY);
        String commitId = update.refUpdates().getFirst().newId().orElseThrow().toHex();
        byte[] firstRead = update.pack();
        firstRead[0] = 0;

        assertThat(repository.refs()).isEmpty();
        assertThat(repository.readObject(new pro.deta.orion.git.parser.v2.id.ObjectId(commitId))).isEmpty();
        try (var target = new InMemoryRepository(new DfsRepositoryDescription("pack consumer"));
             var inserter = target.newObjectInserter()) {
            inserter.newPackParser(new ByteArrayInputStream(update.pack())).parse(NullProgressMonitor.INSTANCE);
            inserter.flush();
            try (var walk = new RevWalk(target)) {
                var commit = walk.parseCommit(ObjectId.fromString(commitId));
                try (var tree = TreeWalk.forPath(target, "config.txt", commit.getTree())) {
                    assertThat(tree).isNotNull();
                    assertThat(target.open(tree.getObjectId(0)).getBytes())
                            .isEqualTo(files("prepared").get("config.txt").content());
                }
            }
        }

        NativeGitRepository another = new InMemoryNativeGitRepositoryProvider()
                .create("another").valueOrFailure("repository");
        for (NativeGitRepository target : List.of(repository, another)) {
            assertThat(target.publishPack(update.pack(), update.refUpdates(), true, GitNativeRepositoryAccessHook.ALLOW_ALL))
                    .extracting(RefUpdateResult::status).containsExactly(RefUpdateResult.Status.APPLIED);
            assertThat(target.loadFiles("main", List.of("config.txt")).files())
                    .containsAllEntriesOf(files("prepared"));
        }
    }

    @Test
    void corruptOrIncompletePackCannotPublishRefs() throws Exception {
        NativeGitRepository repository = new FileNativeGitRepositoryProvider(directory)
                .create("demo").valueOrFailure("repository");
        NativeGitFileUpdate update = repository.prepareFileUpdate(
                "main", files("prepared"), "prepared", GitCommitAuthor.EMPTY);
        byte[] corrupt = update.pack();
        corrupt[corrupt.length - 1] ^= 1;
        byte[] incomplete = Arrays.copyOf(update.pack(), corrupt.length - 1);

        for (byte[] rejected : List.of(corrupt, incomplete)) {
            assertThatThrownBy(() -> repository.publishPack(
                    rejected, update.refUpdates(), true, GitNativeRepositoryAccessHook.ALLOW_ALL))
                    .isInstanceOf(GitOperationException.class);
            assertThat(repository.refs()).isEmpty();
            assertThat(repository.storage().packIds()).isEmpty();
        }
    }

    private static Map<String, GitFile> files(String content) {
        return Map.of("config.txt", GitFile.regular(content.getBytes(StandardCharsets.UTF_8)));
    }
}
