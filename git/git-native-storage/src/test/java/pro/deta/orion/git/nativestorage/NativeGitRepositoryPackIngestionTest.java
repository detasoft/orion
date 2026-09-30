package pro.deta.orion.git.nativestorage;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.fileapi.GitCommitAuthor;
import pro.deta.orion.git.parser.v2.index.PackMetadata;
import pro.deta.orion.git.parser.v2.index.memory.InMemoryIndex;
import pro.deta.orion.git.parser.v2.storage.memory.InMemoryStorage;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeGitRepositoryPackIngestionTest {
    @Test
    void missingPackCannotPublishItsIndexThroughRepository() throws Exception {
        InMemoryStorage storage = new InMemoryStorage();
        try (NativeGitRepository repository = new NativeGitRepository(
                "project.git", storage, new InMemoryIndex(), "refs/heads/main")) {
            byte[] bytes = preparedPack(repository);
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
            byte[] bytes = preparedPack(repository);
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
    private static byte[] preparedPack(NativeGitRepository repository) throws Exception {
        return repository.files().withAccess("main", "initial", GitCommitAuthor.EMPTY, access -> {
            access.write("file", new byte[]{1});
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            access.writePack(new OutputStreamBufferedByteOutput(output));
            return output.toByteArray();
        });
    }
}
