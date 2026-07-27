package pro.deta.orion.git.client.repository;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitRepositoryContentsContractTest {
    @Test
    void completesAndReadsExactBinaryPackWithoutConsumingWrittenBuffers() throws Exception {
        InMemoryGitRepositoryContents contents = new InMemoryGitRepositoryContents();
        ByteBuf first = Unpooled.wrappedBuffer(new byte[]{0, 1, 2});
        ByteBuf second = Unpooled.wrappedBuffer(new byte[]{(byte) 0xff, 4, 5, 6});
        int firstReaderIndex = first.readerIndex();
        int secondReaderIndex = second.readerIndex();

        GitPackId packId;
        try (GitPackWriter writer = contents.beginPack()) {
            writer.write(first);
            writer.write(second);
            assertThat(first.readerIndex()).isEqualTo(firstReaderIndex);
            assertThat(second.readerIndex()).isEqualTo(secondReaderIndex);
            packId = writer.complete();
            assertThat(writer.complete()).isEqualTo(packId);
        } finally {
            first.release();
            second.release();
        }

        assertThat(readAll(contents, packId))
                .containsExactly(0, 1, 2, (byte) 0xff, 4, 5, 6);
    }

    @Test
    void closingIncompleteWriteDoesNotPublishPack() throws Exception {
        InMemoryGitRepositoryContents contents = new InMemoryGitRepositoryContents();

        try (GitPackWriter writer = contents.beginPack()) {
            ByteBuf chunk = Unpooled.wrappedBuffer(new byte[]{1, 2, 3});
            try {
                writer.write(chunk);
            } finally {
                chunk.release();
            }
        }

        assertThat(contents.storedPackCount()).isZero();
    }

    @Test
    void openingUnknownPackReturnsTypedFailure() {
        InMemoryGitRepositoryContents contents = new InMemoryGitRepositoryContents();

        assertThatThrownBy(() -> contents.openPack(new GitPackId("missing")))
                .isInstanceOfSatisfying(GitRepositoryAccessException.class, failure -> {
                    assertThat(failure.operation())
                            .isEqualTo(GitRepositoryAccessException.Operation.OPEN_PACK);
                    assertThat(failure.retryable()).isFalse();
                });
    }

    @Test
    void readingClosedPackReturnsReadFailure() throws Exception {
        InMemoryGitRepositoryContents contents = new InMemoryGitRepositoryContents();
        GitPackId packId;
        try (GitPackWriter writer = contents.beginPack()) {
            packId = writer.complete();
        }
        GitPackReader reader = contents.openPack(packId);
        reader.close();

        assertThatThrownBy(reader::read)
                .isInstanceOfSatisfying(GitRepositoryAccessException.class, failure ->
                        assertThat(failure.operation())
                                .isEqualTo(GitRepositoryAccessException.Operation.READ_PACK));
    }

    private static byte[] readAll(
            InMemoryGitRepositoryContents contents,
            GitPackId packId) throws Exception {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (GitPackReader reader = contents.openPack(packId)) {
            ByteBuf chunk;
            while ((chunk = reader.read()) != null) {
                try {
                    byte[] copy = new byte[chunk.readableBytes()];
                    chunk.readBytes(copy);
                    result.writeBytes(copy);
                } finally {
                    assertThat(chunk.release()).isTrue();
                    assertThat(chunk.refCnt()).isZero();
                }
            }
        }
        return result.toByteArray();
    }
}
