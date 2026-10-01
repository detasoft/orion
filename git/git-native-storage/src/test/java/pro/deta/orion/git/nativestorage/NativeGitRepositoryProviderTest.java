package pro.deta.orion.git.nativestorage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.schema.orion.v2.RepositoryName;
import pro.deta.orion.util.Result;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class NativeGitRepositoryProviderTest {
    @Test
    void retainsOneFileHandleAndFindsItAfterCreation(@TempDir Path directory) {
        try (NativeGitRepositoryProvider provider =
                     new NativeGitRepositoryProvider(new FileNativeGitRepositoryFactory(directory))) {
            NativeGitRepository created = provider.create("team/repo").valueOrFailure("create");
            assertThat(provider.find("team/repo").valueOrFailure("find")).isSameAs(created);
            assertThat(provider.repositoryNames()).containsExactly("team/repo");
            assertThat(provider.create("team/repo")).isInstanceOf(Result.Failure.class);
        }
        try (NativeGitRepositoryProvider reopened =
                     new NativeGitRepositoryProvider(new FileNativeGitRepositoryFactory(directory))) {
            assertThat(reopened.exists("team/repo")).isTrue();
            assertThat(reopened.find("team/repo").valueOrFailure("reopen").name()).isEqualTo("team/repo");
        }
    }

    @Test
    void inMemoryFactoryDoesNotKeepASecondRepositoryRegistry() {
        NativeGitRepositoryBackend factory = new InMemoryNativeGitRepositoryFactory();
        try (NativeGitRepositoryProvider provider = new NativeGitRepositoryProvider(factory)) {
            assertThat(provider.find("team/repo")).isInstanceOf(Result.Failure.class);
            NativeGitRepository created = provider.create("team/repo").valueOrFailure("create");
            assertThat(factory.repositoryNames()).isEmpty();
            assertThat(factory.exists(RepositoryName.parse("team/repo"))).isFalse();
            assertThat(provider.find("team/repo").valueOrFailure("find")).isSameAs(created);
            assertThat(provider.repositoryNames()).containsExactly("team/repo");
        }
    }

    @Test
    void opensCacheCreatedByAnotherOwnerDuringBootstrap() {
        AtomicInteger opens = new AtomicInteger();
        NativeGitRepository cached = NativeGitRepository.createInMemory(RepositoryName.parse("bootstrap"));
        NativeGitRepositoryBackend backing = new NativeGitRepositoryBackend() {
            @Override
            public List<String> repositoryNames() {
                return List.of();
            }

            @Override
            public boolean exists(RepositoryName name) {
                return true;
            }

            @Override
            public Result<NativeGitRepository> open(RepositoryName name) {
                return opens.incrementAndGet() == 1
                        ? new Result.Failure<>(Result.FailureCode.NOT_FOUND, "Not visible yet")
                        : new Result.Success<>(cached);
            }

            @Override
            public Result<NativeGitRepository> create(RepositoryName name) {
                return new Result.Failure<>(Result.FailureCode.FILE_ALREADY_EXISTS, "Created concurrently");
            }

            @Override
            public void close() {
            }
        };
        try (NativeGitRepositoryProvider provider = new NativeGitRepositoryProvider(backing)) {
            assertThat(provider.openBacking("bootstrap", backing)).isSameAs(cached);
            assertThat(opens).hasValue(2);
        }
    }
}
