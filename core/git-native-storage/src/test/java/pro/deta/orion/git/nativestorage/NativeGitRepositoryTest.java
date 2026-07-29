package pro.deta.orion.git.nativestorage;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;

import static org.assertj.core.api.Assertions.assertThat;

class NativeGitRepositoryTest {
    private static final String NULL_ID = "0".repeat(40);
    private static final String MAIN_ID = "1".repeat(40);

    @Test
    void exposesConfiguredIdentityAndEmptyRefSnapshot() {
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new LooseRefStore(),
                new LooseObjectStore(),
                "refs/heads/main");

        assertThat(repository.name()).isEqualTo("demo.git");
        assertThat(repository.defaultHead()).isEqualTo("refs/heads/main");
        assertThat(repository.refs()).isEmpty();
    }

    @Test
    void exposesUpdatedRefsThroughFreshSnapshots() {
        LooseRefStore refs = new LooseRefStore();
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                refs,
                new LooseObjectStore(),
                "refs/heads/main");

        refs.update("refs/heads/main", NULL_ID, MAIN_ID);

        assertThat(repository.refs())
                .containsExactlyEntriesOf(
                        java.util.Map.of("refs/heads/main", MAIN_ID));
    }
}
