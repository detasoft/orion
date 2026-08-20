package pro.deta.orion.git.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.GitRepositoryProvider;
import pro.deta.orion.git.common.GitRepository;
import pro.deta.orion.git.common.GitRepositoryFileNotFoundException;
import pro.deta.orion.git.jgit.JGitRepository;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.util.Result;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitRepositoryProviderVersionedStorageTest {
    private static final String BRANCH = "master";
    private static final VersionedSaveRequest SAVE_REQUEST =
            new VersionedSaveRequest("save files", new UserEmail("storage-test", "storage-test@example.test"));

    @TempDir
    Path tempDir;

    @Test
    void missingRepositoryLoadUsesFindWithoutCreatingRepository() {
        RecordingGitRepositoryProvider provider = new RecordingGitRepositoryProvider(tempDir);
        GitRepositoryProviderVersionedStorage storage =
                new GitRepositoryProviderVersionedStorage(provider, "project.git", BRANCH);

        Result<VersionedFileSnapshot> result = storage.load("acl/orion.xml");

        assertThat(result).isInstanceOf(Result.Failure.class);
        assertThat(((Result.Failure<VersionedFileSnapshot>) result).code()).isEqualTo(Result.FailureCode.NOT_FOUND);
        assertThat(provider.findCalls).isEqualTo(1);
        assertThat(provider.findOrCreateCalls).isZero();
        assertThat(tempDir.resolve("project.git")).doesNotExist();
    }

    @Test
    void saveUsesFindOrCreateAndLoadUsesFind() {
        RecordingGitRepositoryProvider provider = new RecordingGitRepositoryProvider(tempDir);
        GitRepositoryProviderVersionedStorage storage =
                new GitRepositoryProviderVersionedStorage(provider, "team/project.git", BRANCH);

        storage.save(Map.of(
                "acl/orion.xml", bytes("acl"),
                "acl/roles.xml", bytes("roles")), SAVE_REQUEST);

        VersionedFileSnapshot snapshot = storage.load(List.of("acl/orion.xml", "acl/roles.xml"))
                .valueOrFailure("files should load");
        assertThat(stringFiles(snapshot)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "acl/orion.xml", "acl",
                "acl/roles.xml", "roles"));
        assertThat(provider.findOrCreateCalls).isEqualTo(1);
        assertThat(provider.findCalls).isEqualTo(1);
    }

    @Test
    void saveDoesNotCreateRepositoryWhenCreationIsDisabled() {
        RecordingGitRepositoryProvider provider = new RecordingGitRepositoryProvider(tempDir);
        GitRepositoryProviderVersionedStorage storage =
                new GitRepositoryProviderVersionedStorage(provider, "project.git", BRANCH, false);

        assertThatThrownBy(() -> storage.save(Map.of("acl/orion.xml", bytes("acl")), SAVE_REQUEST))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Cannot open git repository project.git");
        assertThat(provider.findCalls).isEqualTo(1);
        assertThat(provider.findOrCreateCalls).isZero();
        assertThat(tempDir.resolve("project.git")).doesNotExist();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, String> stringFiles(VersionedFileSnapshot snapshot) {
        Map<String, String> files = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : snapshot.files().entrySet()) {
            files.put(entry.getKey(), new String(entry.getValue(), StandardCharsets.UTF_8));
        }
        return files;
    }

    private static final class RecordingGitRepositoryProvider implements GitRepositoryProvider {
        private final Path storageRoot;
        private int findCalls;
        private int findOrCreateCalls;

        private RecordingGitRepositoryProvider(Path storageRoot) {
            this.storageRoot = storageRoot;
        }

        @Override
        public boolean exists(String repositoryName) {
            return storageRoot.resolve(repositoryName).toFile().exists();
        }

        @Override
        public Result<GitRepository> find(String repositoryName) {
            findCalls++;
            return open(repositoryName, false);
        }

        @Override
        public Result<GitRepository> findOrCreate(String repositoryName) {
            findOrCreateCalls++;
            return open(repositoryName, true);
        }

        private Result<GitRepository> open(String repositoryName, boolean createIfMissing) {
            try {
                Path repositoryPath = storageRoot.resolve(repositoryName).normalize();
                if (!repositoryPath.startsWith(storageRoot)) {
                    return new Result.Failure<>(Result.FailureCode.GENERAL, "Repository path escapes storage root");
                }
                return new Result.Success<>(JGitRepository.open(repositoryName, repositoryPath, createIfMissing));
            } catch (GitRepositoryFileNotFoundException e) {
                return new Result.Failure<>(Result.FailureCode.NOT_FOUND);
            } catch (IOException | IllegalArgumentException e) {
                return new Result.Failure<>(Result.FailureCode.GENERAL, e.getMessage(), e);
            }
        }
    }
}
