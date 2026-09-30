package pro.deta.orion.git.nativestorage.pack;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.fileapi.GitCommitAuthor;
import pro.deta.orion.git.fileapi.GitFile;
import pro.deta.orion.git.nativestorage.NativeGitFileUpdate;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.index.PackMetadata;
import pro.deta.orion.git.parser.v2.index.memory.InMemoryIndex;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.git.parser.v2.read.GitObjectRead;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.v2.storage.memory.InMemoryStorage;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackIngestionOutputTest {
    @Test
    void ingestsFragmentedBytesAndTransfersPackOwnership() throws Exception {
        {
            try (GitStorageApi storage = new InMemoryStorage()) {
                GitIndexAccess index = new InMemoryIndex().createAccess();
                try {
                    NativeGitFileUpdate prepared = prepared();
                    byte[] bytes = prepared.pack();
                    PackMetadata pack;
                    try (PackIngestionOutput output = new PackIngestionOutput(storage, index)) {
                        for (int offset = 0; offset < bytes.length; offset += 3) {
                            ByteBuf fragment = Unpooled.wrappedBuffer(bytes, offset, Math.min(3, bytes.length - offset));
                            try {
                                int position = fragment.readerIndex();
                                output.write(fragment);
                                assertThat(fragment.readerIndex()).isEqualTo(position);
                            } finally {
                                fragment.release();
                            }
                        }
                        pack = output.complete();
                        assertThat(index.packs()).isEmpty();
                        assertThatThrownBy(() -> output.write(new byte[]{1})).isInstanceOf(IOException.class);
                        assertThatThrownBy(output::complete).isInstanceOf(IOException.class);
                    }
                    ObjectId commit = prepared.refUpdates().getFirst().newId().orElseThrow();
                    assertThat(index.findObject(pack.packId(), commit)).isPresent();
                    index.publishIndex(pack);
                    assertThat(GitObjectRead.exists(storage, index, commit)).isTrue();
                } finally {
                    index.discard();
                }
            }
        }
    }

    @Test
    void rejectsIncompleteCorruptAndTrailingBytesWithoutPublishing() throws Exception {
        byte[] valid = prepared().pack();
        byte[] corrupt = valid.clone();
        corrupt[corrupt.length - 1] ^= 1;
        for (byte[] invalid : new byte[][]{
                Arrays.copyOf(valid, valid.length - 1), corrupt, Arrays.copyOf(valid, valid.length + 1)}) {
            {
                try (GitStorageApi storage = new InMemoryStorage()) {
                    new InMemoryIndex().withAccess(index -> {
                        try (PackIngestionOutput output = new PackIngestionOutput(storage, index)) {
                            output.write(invalid);
                            assertThatThrownBy(output::complete).isInstanceOf(IOException.class);
                            assertThat(index.packs()).isEmpty();
                        }
                        return null;
                    });
                }
            }
        }
    }

    @Test
    void closeAbandonsIncompleteBytesAndRejectsFurtherWrites() throws Exception {
        {
            try (GitStorageApi storage = new InMemoryStorage()) {
                new InMemoryIndex().withAccess(index -> {
                    PackIngestionOutput output = new PackIngestionOutput(storage, index);
                    output.write(new byte[]{'P', 'A'});
                    output.close();
                    output.close();
                    assertThatThrownBy(output::complete).isInstanceOf(IOException.class);
                    assertThatThrownBy(() -> output.write(new byte[]{1})).isInstanceOf(IOException.class);
                    assertThat(index.packs()).isEmpty();
                    return null;
                });
            }
        }
    }

    private static NativeGitFileUpdate prepared() throws Exception {
        InMemoryStorage storage = new InMemoryStorage();
        try (NativeGitRepository repository = new NativeGitRepository(
                "source", storage, new InMemoryIndex(), "refs/heads/main")) {
            return repository.files().prepareFileUpdate(
                    "main", Map.of("file", GitFile.regular(new byte[]{1, 2, 3})), Set.of(),
                    "initial", GitCommitAuthor.EMPTY);
        }
    }
}
