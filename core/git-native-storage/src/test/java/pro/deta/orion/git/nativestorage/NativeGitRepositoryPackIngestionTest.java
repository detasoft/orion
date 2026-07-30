package pro.deta.orion.git.nativestorage;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.pack.PackIngestionLimits;
import pro.deta.orion.git.nativestorage.pack.PackIngestionSession;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeGitRepositoryPackIngestionTest {
    @Test
    void opensIndependentPackIngestionSessions() {
        NativeGitRepository repository = repository();
        PackIngestionLimits limits = new PackIngestionLimits(
                1024,
                10,
                512);

        try (PackIngestionSession first =
                     repository.beginPackIngestion(limits);
             PackIngestionSession second =
                     repository.beginPackIngestion(limits)) {
            assertThat(first).isNotSameAs(second);
        }
    }

    @Test
    void rejectsMissingPackIngestionLimits() {
        assertThatThrownBy(() -> repository().beginPackIngestion(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("limits");
    }

    private static NativeGitRepository repository() {
        return new NativeGitRepository(
                "project.git",
                new LooseRefStore(),
                new LooseObjectStore(),
                "refs/heads/main");
    }
}
