package pro.deta.orion.git.nativestorage;

import org.junit.jupiter.api.Test;
import pro.deta.orion.util.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryNativeGitRepositoryProviderTest {
    @Test
    void canonicalizesNamesBeforeLookupAndCollisionChecks() {
        NativeGitRepositoryProvider provider =
                NativeGitRepositoryProvider.inMemory();

        NativeGitRepository created = provider.create("team%2Frepo")
                .valueOrFailure("repository");

        assertThat(created.name()).isEqualTo("team/repo");
        assertThat(provider.exists("team/repo")).isTrue();
        assertThat(provider.find("team\\repo").valueOrFailure("repository"))
                .isSameAs(created);
        assertThat(provider.create("team/repo")).isInstanceOf(Result.Failure.class);
        assertThat(provider.repositoryNames()).containsExactly("team/repo");
    }

    @Test
    void rejectsInvalidNamesBeforeAccessingStorage() {
        NativeGitRepositoryProvider provider =
                NativeGitRepositoryProvider.inMemory();

        assertThatThrownBy(() -> provider.exists("Repo"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.find("../repo"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.create("repo.git"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
