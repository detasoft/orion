package pro.deta.orion.git;

import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import pro.deta.orion.git.common.GitRepository;
import pro.deta.orion.util.Result;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.git.JGitRuntimeAssertions.assertControlledJGitSystemReaderInstalled;
import static pro.deta.orion.git.JGitRuntimeAssertions.installDefaultControlledJGitRuntime;

@DisplayName("Git repository provider path validation")
@ResourceLock("jgit-system-reader")
class GitRepositoryProviderImplTest {
    @TempDir
    private Path gitStorageDir;

    @BeforeEach
    void installControlledJGitRuntime() {
        installDefaultControlledJGitRuntime();
    }

    @AfterEach
    void resetControlledJGitRuntime() {
        try {
            assertControlledJGitSystemReaderInstalled();
        } finally {
            installDefaultControlledJGitRuntime();
        }
    }

    @Test
    @DisplayName("nested repository names are allowed")
    void nestedRepositoryNamesAreAllowed() {
        GitRepositoryProviderImpl provider = newProvider();

        Result<GitRepository> result = provider.findOrCreate("team/project.git");

        assertThat(result).isInstanceOf(Result.Success.class);
        assertThat(result.valueOrFailure("repository should be created").unwrap(Repository.class)).isPresent();
        assertThat(provider.exists("team/project.git")).isTrue();
        assertThat(provider.repositoryPathForTests("team/project.git").resolve("config")).exists();
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
        assertControlledJGitSystemReaderInstalled();
        return new GitRepositoryProviderImpl(gitStorageDir);
    }
}
