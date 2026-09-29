package pro.deta.orion.git.nativestorage;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.fileapi.GitCommitAuthor;
import pro.deta.orion.git.fileapi.GitFile;
import pro.deta.orion.git.parser.v2.index.memory.InMemoryIndex;
import pro.deta.orion.git.parser.v2.pack.MutableIndexedPack;
import pro.deta.orion.git.parser.v2.storage.memory.InMemoryStorage;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NativeGitRepositoryPackIngestionTest {
    @Test
    void independentIngestionsRemainUnpublishedUntilPersisted() throws Exception {
        InMemoryStorage storage = new InMemoryStorage();
        try (NativeGitRepository repository = new NativeGitRepository(
                "project.git", storage, new InMemoryIndex(), "refs/heads/main")) {
            byte[] bytes = repository.files().prepareFileUpdate(
                    "main", Map.of("file", GitFile.regular(new byte[]{1})), Set.of(),
                    "initial", GitCommitAuthor.EMPTY).pack();
            try (BufferedByteInputV2 firstInput = new BufferedByteInputV2(new ByteArrayInputStream(bytes));
                 BufferedByteInputV2 secondInput = new BufferedByteInputV2(new ByteArrayInputStream(bytes))) {
                MutableIndexedPack first = repository.ingest(firstInput);
                MutableIndexedPack second = repository.ingest(secondInput);
                assertThat(first).isNotSameAs(second);
                assertThat(repository.storage().packIds()).isEmpty();
                first.discard();
                repository.storage().persist(second);
                assertThat(repository.storage().packIds()).hasSize(1);
            }
        }
    }
}
