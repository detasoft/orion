package pro.deta.orion.git.nativestorage;

import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.fileapi.GitCommitAuthor;
import pro.deta.orion.git.fileapi.GitFile;
import pro.deta.orion.git.parser.v2.index.PackMetadata;
import pro.deta.orion.git.parser.v2.index.memory.InMemoryIndex;
import pro.deta.orion.git.parser.v2.storage.memory.InMemoryStorage;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeGitRepositoryPackIngestionTest {
    @Test
    void missingPackCannotPublishItsIndexThroughRepository() throws Exception {
        InMemoryStorage storage = new InMemoryStorage();
        try (NativeGitRepository repository = new NativeGitRepository(
                "project.git", storage, new InMemoryIndex(), "refs/heads/main")) {
            byte[] bytes = FileUpdateFixture.prepare(repository.files(),
                    "main", Map.of("file", GitFile.regular(new byte[]{1})), Set.of(),
                    "initial", GitCommitAuthor.EMPTY).pack();
            try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(bytes))) {
                PackMetadata pack = repository.ingest(input);
                storage.close();
                assertThatThrownBy(() -> repository.publishPack(pack)).isInstanceOf(IOException.class);
                repository.index().withAccess(access1 -> {
                    assertThat(access1.packs()).isEmpty();
                    assertThat(access1.snapshotRefs().refs()).isEmpty();
                    return null;
                });
            }
        }
    }

    @Test
    void independentIngestionsRemainUnpublishedUntilPersisted() throws Exception {
        InMemoryStorage storage = new InMemoryStorage();
        try (NativeGitRepository repository = new NativeGitRepository(
                "project.git", storage, new InMemoryIndex(), "refs/heads/main")) {
            byte[] bytes = FileUpdateFixture.prepare(repository.files(),
                    "main", Map.of("file", GitFile.regular(new byte[]{1})), Set.of(),
                    "initial", GitCommitAuthor.EMPTY).pack();
            try (BufferedByteInputV2 firstInput = new BufferedByteInputV2(new ByteArrayInputStream(bytes));
                 BufferedByteInputV2 secondInput = new BufferedByteInputV2(new ByteArrayInputStream(bytes))) {
                PackMetadata first = repository.ingest(firstInput);
                PackMetadata second = repository.ingest(secondInput);
                assertThat(first).isNotSameAs(second);
                repository.index().withAccess(access2 -> {
                    assertThat(access2.packs()).isEmpty();
                    assertThat(first.packId()).isNotEqualTo(second.packId());
                    repository.publishPack(second);
                    assertThat(access2.packs()).hasSize(1);
                    return null;
                });
            }
        }
    }
}
