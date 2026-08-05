package pro.deta.orion.git.nativestorage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.util.Result;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileNativeGitRepositoryProviderTest {
    private static final String NULL_ID = "0".repeat(40);

    @Test
    void reopensPersistedRefsAndObjects(@TempDir Path rootDirectory) {
        FileNativeGitRepositoryProvider first =
                new FileNativeGitRepositoryProvider(rootDirectory);
        NativeGitRepository repository = first.findOrCreate(
                "team/project.git").valueOrFailure("repository");
        GitObjectId blob = repository.writeObject(
                ObjectType.BLOB,
                "persistent".getBytes(StandardCharsets.UTF_8));
        repository.updateRef("refs/heads/main", NULL_ID, blob.value());

        FileNativeGitRepositoryProvider second =
                new FileNativeGitRepositoryProvider(rootDirectory);
        NativeGitRepository reopened = second.find("team/project.git")
                .valueOrFailure("repository");

        assertThat(reopened.name()).isEqualTo("team/project.git");
        assertThat(reopened.defaultHead()).isEqualTo("refs/heads/main");
        assertThat(reopened.refs())
                .containsEntry("refs/heads/main", blob.value());
        assertThat(reopened.readObject(blob))
                .isPresent()
                .get()
                .satisfies(object -> {
                    assertThat(object.type()).isEqualTo(ObjectType.BLOB);
                    assertThat(object.data()).isEqualTo(
                            "persistent".getBytes(StandardCharsets.UTF_8));
                });
    }

    @Test
    void repositoryNamesDoNotMapDirectlyToFileSystemPaths(
            @TempDir Path rootDirectory) {
        FileNativeGitRepositoryProvider provider =
                new FileNativeGitRepositoryProvider(rootDirectory);

        provider.findOrCreate("team/project.git")
                .valueOrFailure("repository");

        assertThat(Files.exists(rootDirectory.resolve("team"))).isFalse();
    }

    @Test
    void findDoesNotCreateRepository(@TempDir Path rootDirectory) {
        FileNativeGitRepositoryProvider provider =
                new FileNativeGitRepositoryProvider(rootDirectory);

        Result<NativeGitRepository> result = provider.find("missing.git");

        assertThat(result).isInstanceOf(Result.Failure.class);
        assertThat(provider.exists("missing.git")).isFalse();
    }
}
