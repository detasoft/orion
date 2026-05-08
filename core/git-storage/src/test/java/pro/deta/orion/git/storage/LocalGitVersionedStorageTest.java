package pro.deta.orion.git.storage;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.util.Result;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LocalGitVersionedStorageTest {
    private static final String BRANCH = "master";
    private static final UserEmail AUTHOR = new UserEmail("storage-test", "storage-test@example.test");
    private static final VersionedSaveRequest SAVE_REQUEST = new VersionedSaveRequest("seed files", AUTHOR);

    @TempDir
    Path tempDir;

    @Test
    void missingRepositoryDoesNotLoadWhenCreationIsDisabled() {
        Path repositoryPath = tempDir.resolve("project.git");
        LocalGitVersionedStorage storage = new LocalGitVersionedStorage(repositoryPath, BRANCH, false);

        Result<VersionedFileSnapshot> result = storage.load("acl/orion.xml");

        assertThat(result).isInstanceOf(Result.Failure.class);
        assertThat(((Result.Failure<VersionedFileSnapshot>) result).code()).isEqualTo(Result.FailureCode.NOT_FOUND);
        assertThat(repositoryPath).doesNotExist();
    }

    @Test
    void savingCreatesBareRepositoryWhenCreationIsEnabled() throws Exception {
        Path repositoryPath = tempDir.resolve("nested storage path").resolve("project.git");
        LocalGitVersionedStorage storage = new LocalGitVersionedStorage(repositoryPath, BRANCH);

        storage.save(Map.of("acl/orion.xml", bytes("acl")), SAVE_REQUEST);

        try (Repository jgitRepository = FileRepositoryBuilder.create(repositoryPath.toFile())) {
            assertThat(jgitRepository.isBare()).isTrue();
            assertThat(jgitRepository.getObjectDatabase().exists()).isTrue();
        }
    }

    @Test
    void loadingUnsafePathReturnsFailure() {
        LocalGitVersionedStorage storage = new LocalGitVersionedStorage(tempDir.resolve("project.git"), BRANCH);
        storage.save(Map.of("acl/orion.xml", bytes("acl")), SAVE_REQUEST);

        Result<VersionedFileSnapshot> result = storage.load("../acl.xml");

        assertThat(result).isInstanceOf(Result.Failure.class);
        assertThat(((Result.Failure<VersionedFileSnapshot>) result).code()).isEqualTo(Result.FailureCode.GENERAL);
    }

    @Test
    void storesAndLoadsMultipleFilesOnBranch() {
        LocalGitVersionedStorage storage = new LocalGitVersionedStorage(tempDir.resolve("team").resolve("project.git"), BRANCH);

        storage.save(Map.of(
                "acl/orion.xml", bytes("acl"),
                "acl/roles.xml", bytes("roles")), SAVE_REQUEST);

        VersionedFileSnapshot snapshot = storage.load(List.of("acl/orion.xml", "acl/roles.xml"))
                .valueOrFailure("files should load");
        assertThat(stringFiles(snapshot)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "acl/orion.xml", "acl",
                "acl/roles.xml", "roles"));
        assertThat(snapshot.version()).isPresent();
    }

    @Test
    void overwritesFileWithoutDroppingUnchangedFiles() {
        LocalGitVersionedStorage storage = new LocalGitVersionedStorage(tempDir.resolve("project.git"), BRANCH);
        storage.save(Map.of(
                "acl/orion.xml", bytes("old acl"),
                "acl/roles.xml", bytes("roles")), SAVE_REQUEST);
        String firstVersion = storage.load("acl/orion.xml")
                .valueOrFailure("initial file should load")
                .version()
                .orElseThrow();

        storage.save(Map.of("acl/orion.xml", bytes("new acl")), new VersionedSaveRequest("overwrite acl", AUTHOR));

        VersionedFileSnapshot snapshot = storage.load(List.of("acl/orion.xml", "acl/roles.xml"))
                .valueOrFailure("files should load after overwrite");
        assertThat(stringFiles(snapshot)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "acl/orion.xml", "new acl",
                "acl/roles.xml", "roles"));
        assertThat(snapshot.version()).isPresent();
        assertThat(snapshot.version().orElseThrow()).isNotEqualTo(firstVersion);
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
}
