package pro.deta.orion.git.nativestorage;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.fileapi.GitCommitAuthor;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.index.PackMetadata;
import pro.deta.orion.git.parser.v2.index.memory.InMemoryIndex;
import pro.deta.orion.git.parser.v2.read.GitPackRead;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.v2.storage.GitStorageAccess;
import pro.deta.orion.git.parser.v2.storage.memory.InMemoryStorage;
import pro.deta.orion.git.parser.v2.storage.shared.PackHandle;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeGitRepositoryPackIngestionTest {
    @Test
    void missingPackCannotPublishItsIndexAndAccessRemainsOpenUntilStorageCheck() throws Exception {
        InMemoryIndex index = new InMemoryIndex();
        TrackingStorage storage = new TrackingStorage(index);
        storage.missing = true;
        try (NativeGitRepository repository = new NativeGitRepository(
                "project.git", storage, index, "refs/heads/main")) {
            byte[] bytes = preparedPack(repository);
            try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(bytes))) {
                assertThatThrownBy(() -> repository.ingestAndPublish(input)).isInstanceOf(IOException.class)
                        .hasMessageContaining("Cannot publish missing pack");
                assertThat(storage.checked).isTrue();
                assertThat(index.activeAccesses()).isEmpty();
                repository.index().withAccess(access1 -> {
                    assertThat(access1.packs()).isEmpty();
                    assertThat(access1.snapshotRefs().refs()).isEmpty();
                    return null;
                });
            }
        }
    }

    @Test
    void independentIngestionsPublishDistinctPacksWithTheSameChecksum() throws Exception {
        InMemoryStorage storage = new InMemoryStorage();
        try (NativeGitRepository repository = new NativeGitRepository(
                "project.git", storage, new InMemoryIndex(), "refs/heads/main")) {
            byte[] bytes = preparedPack(repository);
            try (BufferedByteInputV2 firstInput = new BufferedByteInputV2(new ByteArrayInputStream(bytes));
                 BufferedByteInputV2 secondInput = new BufferedByteInputV2(new ByteArrayInputStream(bytes))) {
                PackMetadata first = repository.ingestAndPublish(firstInput);
                PackMetadata second = repository.ingestAndPublish(secondInput);
                assertThat(first).isNotSameAs(second);
                repository.index().withAccess(access2 -> {
                    assertThat(access2.packs()).containsExactlyInAnyOrder(first, second);
                    assertThat(first.packId()).isNotEqualTo(second.packId());
                    assertThat(first.packChecksum()).isEqualTo(second.packChecksum());
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

    private static final class TrackingStorage implements GitStorageApi {
        private final InMemoryStorage bytes = new InMemoryStorage();
        private final InMemoryIndex index;
        private boolean missing;
        private boolean checked;

        private TrackingStorage(InMemoryIndex index) {
            this.index = index;
        }

        @Override
        public GitStorageAccess createAccess() throws IOException {
            GitStorageAccess access = bytes.createAccess();
            return new GitStorageAccess() {
                public PackHandle newPack(PackId packId) throws IOException {
                    return access.newPack(packId);
                }
                public <R> R readPack(PackId packId, long offset, long length, GitPackRead<R> reader)
                        throws IOException {
                    return access.readPack(packId, offset, length, reader);
                }
                public boolean exists(PackId packId) throws IOException {
                    assertThat(index.activeAccesses()).anySatisfy(active -> assertThat(active.packId()).contains(packId));
                    checked = true;
                    return !missing && access.exists(packId);
                }
                public void close() throws IOException { access.close(); }
            };
        }

        @Override
        public void close() {
            bytes.close();
        }
    }
}
