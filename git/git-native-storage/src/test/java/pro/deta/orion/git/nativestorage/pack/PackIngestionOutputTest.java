package pro.deta.orion.git.nativestorage.pack;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.GitFile;
import pro.deta.orion.git.nativestorage.NativeGitFileUpdate;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackIngestionOutputTest {
    @Test
    void ingestsFragmentedBytesAndTransfersPackOwnership() throws Exception {
        try (GitStorageApi storage = new GitStorageApi()) {
            NativeGitFileUpdate prepared = prepared();
            byte[] bytes = prepared.pack();
            IndexedPack pack;
            try (PackIngestionOutput output = new PackIngestionOutput(storage)) {
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
                assertThat(storage.packIds()).isEmpty();
                assertThatThrownBy(() -> output.write(new byte[]{1})).isInstanceOf(IOException.class);
                assertThatThrownBy(output::complete).isInstanceOf(IOException.class);
            }
            ObjectId commit = prepared.refUpdates().getFirst().newId().orElseThrow();
            assertThat(pack.find(commit)).isPresent();
            storage.persist(pack);
            assertThat(storage.exists(commit)).isTrue();
        }
    }

    @Test
    void rejectsIncompleteCorruptAndTrailingBytesWithoutPublishing() throws Exception {
        byte[] valid = prepared().pack();
        byte[] corrupt = valid.clone();
        corrupt[corrupt.length - 1] ^= 1;
        for (byte[] invalid : new byte[][]{
                Arrays.copyOf(valid, valid.length - 1), corrupt, Arrays.copyOf(valid, valid.length + 1)}) {
            try (GitStorageApi storage = new GitStorageApi();
                 PackIngestionOutput output = new PackIngestionOutput(storage)) {
                output.write(invalid);
                assertThatThrownBy(output::complete).isInstanceOf(IOException.class);
                assertThat(storage.packIds()).isEmpty();
            }
        }
    }

    @Test
    void closeAbandonsIncompleteBytesAndRejectsFurtherWrites() throws Exception {
        try (GitStorageApi storage = new GitStorageApi()) {
            PackIngestionOutput output = new PackIngestionOutput(storage);
            output.write(new byte[]{'P', 'A'});
            output.close();
            output.close();
            assertThatThrownBy(output::complete).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> output.write(new byte[]{1})).isInstanceOf(IOException.class);
            assertThat(storage.packIds()).isEmpty();
        }
    }

    private static NativeGitFileUpdate prepared() throws Exception {
        try (NativeGitRepository repository = new NativeGitRepository(
                "source", new GitStorageApi(), "refs/heads/main")) {
            return repository.prepareFileUpdate("main", Map.of("file", GitFile.regular(new byte[]{1, 2, 3})),
                    "initial", GitCommitAuthor.EMPTY);
        }
    }
}
