package pro.deta.orion.git.storage;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.common.GitCommitAuthor;
import pro.deta.orion.git.common.GitRepository;
import pro.deta.orion.git.common.GitRepositoryFileSnapshot;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.util.Result;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LocalRepositoryStorageTest {
    private static final String BRANCH = "master";
    private static final UserEmail AUTHOR = new UserEmail("storage-test", "storage-test@example.test");
    private static final GitCommitAuthor GIT_AUTHOR = new GitCommitAuthor(AUTHOR.getUsername(), AUTHOR.getEmail());

    @TempDir
    Path tempDir;

    @Test
    void supportsFileUriAndLegacyPathLocators() {
        RepositoryStorageLocator fileLocator = RepositoryStorageLocator.parse(tempDir.toUri().toString());
        RepositoryStorageLocator legacyLocator = RepositoryStorageLocator.parse(tempDir.resolve("legacy").toString());
        RepositoryStorageLocator unsupportedLocator = RepositoryStorageLocator.parse("s3://bucket/repositories");

        LocalRepositoryStorage storage = new LocalRepositoryStorage(fileLocator, false);

        assertThat(storage.supports(fileLocator)).isTrue();
        assertThat(storage.supports(legacyLocator)).isTrue();
        assertThat(storage.supports(unsupportedLocator)).isFalse();
    }

    @Test
    void missingRepositoryDoesNotOpenWhenCreationIsDisabled() {
        LocalRepositoryStorage storage = new LocalRepositoryStorage(RepositoryStorageLocator.parse(tempDir.toUri().toString()), false);

        Result<GitRepository> result = storage.open("project");

        assertThat(result).isInstanceOf(Result.Failure.class);
        assertThat(((Result.Failure<GitRepository>) result).code()).isEqualTo(Result.FailureCode.NOT_FOUND);
        assertThat(tempDir.resolve("project")).doesNotExist();
    }

    @Test
    void openingRepositoryCreatesBareRepositoryWhenCreationIsEnabled() throws Exception {
        Path storageRoot = tempDir.resolve("legacy storage path");
        LocalRepositoryStorage storage = new LocalRepositoryStorage(RepositoryStorageLocator.parse(storageRoot.toString()), true);

        try (GitRepository repository = storage.open("project").valueOrFailure("repository should be created")) {
            assertThat(repository).isNotNull();
        }
        try (Repository jgitRepository = FileRepositoryBuilder.create(storageRoot.resolve("project").toFile())) {
            assertThat(jgitRepository.isBare()).isTrue();
            assertThat(jgitRepository.getObjectDatabase().exists()).isTrue();
        }
    }

    @Test
    void rejectsUnsafeRepositoryNames() {
        LocalRepositoryStorage storage = new LocalRepositoryStorage(RepositoryStorageLocator.parse(tempDir.toUri().toString()), true);
        String[] unsafeNames = {"", " ", "../project", "team/../project", tempDir.resolve("absolute").toString()};

        for (String unsafeName : unsafeNames) {
            Result<GitRepository> result = storage.open(unsafeName);

            assertThat(result).as("unsafe repository name %s", unsafeName).isInstanceOf(Result.Failure.class);
            assertThat(((Result.Failure<GitRepository>) result).code()).isEqualTo(Result.FailureCode.GENERAL);
        }
    }

    @Test
    void storesAndLoadsMultipleFilesOnBranch() throws Exception {
        LocalRepositoryStorage storage = new LocalRepositoryStorage(RepositoryStorageLocator.parse(tempDir.toUri().toString()), true);
        try (GitRepository repository = storage.open("team/project")
                .valueOrFailure("repository should be created")) {
            repository.saveFiles(BRANCH, Map.of(
                    "acl/orion.xml", bytes("acl"),
                    "acl/roles.xml", bytes("roles")), "seed files", GIT_AUTHOR);

            GitRepositoryFileSnapshot snapshot = repository.loadFiles(BRANCH, List.of("acl/orion.xml", "acl/roles.xml"));
            assertThat(stringFiles(snapshot)).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "acl/orion.xml", "acl",
                    "acl/roles.xml", "roles"));
            assertThat(snapshot.version()).isPresent();
        }
    }

    @Test
    void overwritesFileWithoutDroppingUnchangedFiles() throws Exception {
        LocalRepositoryStorage storage = new LocalRepositoryStorage(RepositoryStorageLocator.parse(tempDir.toUri().toString()), true);
        try (GitRepository repository = storage.open("project").valueOrFailure("repository should be created")) {
            repository.saveFiles(BRANCH, Map.of(
                    "acl/orion.xml", bytes("old acl"),
                    "acl/roles.xml", bytes("roles")), "seed files", GIT_AUTHOR);
            String firstVersion = repository.loadFiles(BRANCH, List.of("acl/orion.xml"))
                    .version()
                    .orElseThrow();

            repository.saveFiles(BRANCH, Map.of("acl/orion.xml", bytes("new acl")), "overwrite acl", GIT_AUTHOR);

            GitRepositoryFileSnapshot snapshot = repository.loadFiles(BRANCH, List.of("acl/orion.xml", "acl/roles.xml"));
            assertThat(stringFiles(snapshot)).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "acl/orion.xml", "new acl",
                    "acl/roles.xml", "roles"));
            assertThat(snapshot.version()).isPresent();
            assertThat(snapshot.version().orElseThrow()).isNotEqualTo(firstVersion);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, String> stringFiles(GitRepositoryFileSnapshot snapshot) {
        Map<String, String> files = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : snapshot.files().entrySet()) {
            files.put(entry.getKey(), new String(entry.getValue(), StandardCharsets.UTF_8));
        }
        return files;
    }
}
