package pro.deta.orion.git;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.util.Result;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Git repository provider path validation")
class GitRepositoryProviderImplTest {
    @TempDir
    private Path gitStorageDir;

    @Test
    @DisplayName("nested repository names are allowed")
    void nestedRepositoryNamesAreAllowed() {
        GitRepositoryProviderImpl provider = newProvider();

        Result<Repository> result = provider.findOrCreate("team/project.git");

        assertThat(result).isInstanceOf(Result.Success.class);
        assertThat(provider.exists("team/project.git")).isTrue();
        assertThat(gitStorageDir.resolve("team/project.git/config")).exists();
    }

    @Test
    @DisplayName("unsafe repository names are rejected")
    void unsafeRepositoryNamesAreRejected() {
        GitRepositoryProviderImpl provider = newProvider();
        Path outsideRepository = gitStorageDir.resolveSibling("outside.git");
        List<String> invalidNames = List.of(
                "",
                " ",
                ".",
                "..",
                "../outside.git",
                "team/../outside.git",
                gitStorageDir.resolve("absolute.git").toString());

        for (String invalidName : invalidNames) {
            assertThat(provider.exists(invalidName)).isFalse();
            assertThat(provider.find(invalidName)).isInstanceOf(Result.Failure.class);
            assertThat(provider.findOrCreate(invalidName)).isInstanceOf(Result.Failure.class);
        }
        assertThat(outsideRepository).doesNotExist();
        assertThat(gitStorageDir.resolve("absolute.git")).doesNotExist();
    }

    private GitRepositoryProviderImpl newProvider() {
        return new GitRepositoryProviderImpl(gitStorageDir, new OrionJGitSystemReader(SystemReader.getInstance(), gitStorageDir.resolve("work")));
    }
}
