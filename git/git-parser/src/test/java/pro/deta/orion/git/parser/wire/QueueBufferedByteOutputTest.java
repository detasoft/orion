package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
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

class QueueBufferedByteOutputTest {
    @Test
    void writeWaitsUntilReaderAcceptsBytes() throws Exception {
        QueueBufferedByteOutput output = new QueueBufferedByteOutput(
                1,
                Duration.ofSeconds(1));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ByteBuf source = Unpooled.wrappedBuffer(
                    "ab".getBytes(StandardCharsets.US_ASCII));
            Future<Void> result = executor.submit(() -> {
                output.write(source);
                return null;
            });

            assertThat(output.takeByte()).isEqualTo((byte) 'a');
            assertThat(output.takeByte()).isEqualTo((byte) 'b');
            result.get(2, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            output.close();
        }
    }

    @Test
    void writeFailsWhenTimeoutExpiresBeforeReaderAcceptsBytes()
            throws Exception {
        QueueBufferedByteOutput output = new QueueBufferedByteOutput(
                1,
                Duration.ofMillis(25));
        try {
            ByteBuf source = Unpooled.wrappedBuffer(
                    "ab".getBytes(StandardCharsets.US_ASCII));
            try {
                assertThatThrownBy(() -> output.write(source))
                        .isInstanceOf(IOException.class)
                        .hasMessageContaining("Timed out");
            } finally {
                source.release();
            }
        } finally {
            output.close();
        }
    }
}
