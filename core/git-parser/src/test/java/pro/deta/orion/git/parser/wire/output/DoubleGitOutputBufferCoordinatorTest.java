package pro.deta.orion.git.parser.wire.output;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DoubleGitOutputBufferCoordinatorTest {
    @Test
    void alternatesBetweenTwoWritableBuffers() {
        Driver driver = new Driver();

        try {
            ByteBuf first = driver.coordinator.writableBuffer();
            first.writeCharSequence("abcd", StandardCharsets.US_ASCII);
            driver.coordinator.submitReady();

            ByteBuf second = driver.coordinator.writableBuffer();
            second.writeCharSequence("ef", StandardCharsets.US_ASCII);
            driver.coordinator.submitReady();

            assertThat(driver.submitted).containsExactly(first, second);
            assertThat(snapshot(first)).containsExactly(
                    "abcd".getBytes(StandardCharsets.US_ASCII));
            assertThat(snapshot(second)).containsExactly(
                    "ef".getBytes(StandardCharsets.US_ASCII));
        } finally {
            driver.close();
        }
    }

    @Test
    void keepsFirstBufferImmutableWhileSecondIsWritable() {
        Driver driver = new Driver();

        try {
            ByteBuf first = driver.coordinator.writableBuffer();
            first.writeCharSequence("abcd", StandardCharsets.US_ASCII);
            driver.coordinator.submitReady();
            byte[] firstSnapshot = snapshot(first);

            ByteBuf second = driver.coordinator.writableBuffer();
            second.writeCharSequence("efgh", StandardCharsets.US_ASCII);

            assertThat(snapshot(first)).containsExactly(firstSnapshot);
            assertThat(snapshot(second)).containsExactly(
                    "efgh".getBytes(StandardCharsets.US_ASCII));
        } finally {
            driver.close();
        }
    }

    @Test
    void waitsWhenBothBuffersAreInFlight() {
        Driver driver = new Driver();

        try {
            driver.coordinator.writableBuffer()
                    .writeCharSequence("abcd", StandardCharsets.US_ASCII);
            driver.coordinator.submitReady();
            driver.coordinator.writableBuffer()
                    .writeCharSequence("efgh", StandardCharsets.US_ASCII);
            driver.coordinator.submitReady();

            CompletionStage<Void> writable =
                    driver.coordinator.awaitWritable();

            assertThat(writable.toCompletableFuture().isDone()).isFalse();
        } finally {
            driver.close();
        }
    }

    @Test
    void completionReclaimsExactlyTheCompletedBuffer() {
        Driver driver = new Driver();

        try {
            ByteBuf first = driver.coordinator.writableBuffer();
            first.writeCharSequence("abcd", StandardCharsets.US_ASCII);
            driver.coordinator.submitReady();
            ByteBuf second = driver.coordinator.writableBuffer();
            second.writeCharSequence("efgh", StandardCharsets.US_ASCII);
            driver.coordinator.submitReady();
            CompletionStage<Void> writable =
                    driver.coordinator.awaitWritable();

            driver.completions.get(1).complete(null);

            assertThat(writable.toCompletableFuture().isDone()).isTrue();
            assertThat(driver.coordinator.writableBuffer()).isSameAs(second);
            assertThat(second.readerIndex()).isZero();
            assertThat(second.writerIndex()).isZero();
            assertThat(snapshot(first)).containsExactly(
                    "abcd".getBytes(StandardCharsets.US_ASCII));
        } finally {
            driver.close();
        }
    }

    @Test
    void writeFailurePreventsReuseUntilClose() {
        Driver driver = new Driver();
        IllegalStateException failure = new IllegalStateException("failed");

        try {
            driver.coordinator.writableBuffer()
                    .writeCharSequence("abcd", StandardCharsets.US_ASCII);
            driver.coordinator.submitReady();

            driver.completions.getFirst().completeExceptionally(failure);

            assertThat(driver.coordinator.awaitWritable()
                    .toCompletableFuture())
                    .isCompletedExceptionally();
            assertThatThrownBy(driver.coordinator::writableBuffer)
                    .isInstanceOf(CompletionException.class)
                    .hasCause(failure);
        } finally {
            driver.close();
        }
    }

    @Test
    void finishWaitsForInFlightWrites() {
        Driver driver = new Driver();

        try {
            driver.coordinator.writableBuffer()
                    .writeCharSequence("abcd", StandardCharsets.US_ASCII);
            CompletionStage<Void> finished = driver.coordinator.finish();

            assertThat(driver.submitted).hasSize(1);
            assertThat(finished.toCompletableFuture().isDone()).isFalse();

            driver.completions.getFirst().complete(null);

            assertThat(finished.toCompletableFuture().isDone()).isTrue();
        } finally {
            driver.close();
        }
    }

    @Test
    void closeIsIdempotentAndReleasesBuffersExactlyOnce() {
        Driver driver = new Driver();

        driver.close();
        driver.close();

        assertThat(driver.first.refCnt()).isZero();
        assertThat(driver.second.refCnt()).isZero();
    }

    private static byte[] snapshot(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    private static final class Driver implements AutoCloseable {
        private final ByteBuf first = Unpooled.buffer(4, 4);
        private final ByteBuf second = Unpooled.buffer(4, 4);
        private final List<ByteBuf> submitted = new ArrayList<>();
        private final List<CompletableFuture<Void>> completions =
                new ArrayList<>();
        private final DoubleGitOutputBufferCoordinator coordinator =
                new DoubleGitOutputBufferCoordinator(
                        first,
                        second,
                        buffer -> {
                            submitted.add(buffer);
                            CompletableFuture<Void> completion =
                                    new CompletableFuture<>();
                            completions.add(completion);
                            return completion;
                        });

        @Override
        public void close() {
            coordinator.close();
        }
    }
}
