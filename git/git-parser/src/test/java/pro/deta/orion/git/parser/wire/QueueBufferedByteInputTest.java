package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueueBufferedByteInputTest {
    @Test
    void readCopyWaitsForBytesFedFromAnotherThread() throws Exception {
        try (QueueBufferedByteInput input = new QueueBufferedByteInput(Duration.ofSeconds(1))) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<String> result = executor.submit(() -> {
                    ByteBuf copy = input.readCopy(3, UnpooledByteBufAllocator.DEFAULT);
                    try {
                        return copy.toString(StandardCharsets.US_ASCII);
                    } finally {
                        copy.release();
                    }
                });

                input.feed("a");
                input.feed("b");
                input.feed("c");

                assertThat(result.get(2, TimeUnit.SECONDS)).isEqualTo("abc");
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void readCopyFailsWhenTimeoutExpiresBeforeRequestedBytesArrive()
            throws Exception {
        try (QueueBufferedByteInput input = new QueueBufferedByteInput(Duration.ofMillis(25))) {
            assertThatThrownBy(() -> input.readCopy(1, UnpooledByteBufAllocator.DEFAULT))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Timed out");
        }
    }
}
