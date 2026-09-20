package pro.deta.orion.git.nativestorage;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.ByteArrayInputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NativeGitRepositoryPackIngestionTest {
    @Test
    void independentIngestionsRemainUnpublishedUntilPersisted() throws Exception {
        try (NativeGitRepository repository = new NativeGitRepository(
                "project.git", new GitStorageApi(), "refs/heads/main")) {
            byte[] bytes = repository.prepareFileUpdate("main", Map.of("file", new byte[]{1}),
                    "initial", GitCommitAuthor.EMPTY).pack();
            try (BufferedByteInputV2 firstInput = new BufferedByteInputV2(new ByteArrayInputStream(bytes));
                 BufferedByteInputV2 secondInput = new BufferedByteInputV2(new ByteArrayInputStream(bytes))) {
                IndexedPack first = repository.ingest(firstInput);
                IndexedPack second = repository.ingest(secondInput);
                assertThat(first).isNotSameAs(second);
                assertThat(repository.storage().packIds()).isEmpty();
                first.discard();
                repository.storage().persist(second);
                assertThat(repository.storage().packIds()).hasSize(1);
            }
        }
    }
}
