package pro.deta.orion.git.parser.wire.output;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class GitOutputBufferCoordinatorContractTest {
    @Test
    void doubleCoordinatorWritesBoundaryPayloadsInOrder() {
        int capacity = 8;
        int[] lengths = {
                1,
                capacity - 1,
                capacity,
                capacity + 1,
                capacity * 2 + 3
        };
        for (int length : lengths) {
            List<byte[]> chunks = new ArrayList<>();
            DoubleGitOutputBufferCoordinator coordinator =
                    new DoubleGitOutputBufferCoordinator(
                            Unpooled.buffer(capacity, capacity),
                            Unpooled.buffer(capacity, capacity),
                            buffer -> {
                                chunks.add(snapshot(buffer));
                                return CompletableFuture.completedFuture(null);
                            });

            try {
                byte[] payload = payload(length);
                writePayload(coordinator, payload);

                assertThat(join(chunks)).containsExactly(payload);
            } finally {
                coordinator.close();
            }
        }
    }

    private static void writePayload(
            GitOutputBufferCoordinator coordinator,
            byte[] payload) {
        int offset = 0;
        while (offset < payload.length) {
            ByteBuf buffer = coordinator.writableBuffer();
            int length = Math.min(
                    buffer.writableBytes(),
                    payload.length - offset);
            buffer.writeBytes(payload, offset, length);
            offset += length;
            if (!buffer.isWritable()) {
                coordinator.submitReady()
                        .toCompletableFuture()
                        .join();
            }
        }
        coordinator.finish()
                .toCompletableFuture()
                .join();
    }

    private static byte[] payload(int length) {
        byte[] payload = new byte[length];
        for (int index = 0; index < length; index++) {
            payload[index] = (byte) ('a' + index % 23);
        }
        return payload;
    }

    private static byte[] snapshot(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    private static byte[] join(List<byte[]> chunks) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) {
            output.writeBytes(chunk);
        }
        return output.toByteArray();
    }
}
