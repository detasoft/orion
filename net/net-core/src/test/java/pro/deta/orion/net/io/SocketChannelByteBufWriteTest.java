package pro.deta.orion.net.io;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SocketChannelByteBufWriteTest {
    @Test
    void returnsWhenReadableBytesAreCopiedIntoSocketSendBuffer() throws Exception {
        try (SocketPair sockets = SocketPair.open()) {
            BufferedByteOutput writer = new SocketChannelByteBufWrite(sockets.client());
            ByteBuf buffer = Unpooled.copiedBuffer("hello", StandardCharsets.US_ASCII);
            try {
                writer.write(buffer);
                writer.flush();

                ByteBuffer received = ByteBuffer.allocate(5);
                while (received.hasRemaining()) {
                    sockets.server().read(received);
                }
                received.flip();
                assertThat(StandardCharsets.US_ASCII.decode(received).toString()).isEqualTo("hello");
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    @Timeout(10)
    void blocksWhenSocketSendBufferCannotAcceptAllReadableBytes() throws Exception {
        try (SocketPair sockets = SocketPair.open();
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            sockets.client().socket().setSendBufferSize(4096);
            sockets.server().socket().setReceiveBufferSize(4096);
            BufferedByteOutput writer = new SocketChannelByteBufWrite(sockets.client());
            int payloadSize = 16 * 1024 * 1024;
            ByteBuf buffer = Unpooled.buffer(payloadSize, payloadSize);
            buffer.writeZero(payloadSize);
            try {
                Future<?> writeTask = executor.submit(() -> {
                    writer.write(buffer);
                    return null;
                });

                Thread.sleep(Duration.ofMillis(100));

                assertThat(writeTask).isNotDone();

                sockets.server().close();
                assertThatThrownBy(() -> writeTask.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(java.io.IOException.class);
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void rejectsNonBlockingSocketChannelBecauseBackPressureMustBlock() throws Exception {
        try (SocketPair sockets = SocketPair.open()) {
            sockets.client().configureBlocking(false);

            assertThatThrownBy(() -> new SocketChannelByteBufWrite(sockets.client()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("channel must be blocking");
        }
    }

    private record SocketPair(SocketChannel client, SocketChannel server) implements AutoCloseable {

        static SocketPair open() throws Exception {
            try (ServerSocketChannel listener = ServerSocketChannel.open()) {
                listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
                SocketChannel client = SocketChannel.open(listener.getLocalAddress());
                SocketChannel server = listener.accept();
                client.configureBlocking(true);
                server.configureBlocking(true);
                return new SocketPair(client, server);
            }
        }

        @Override
        public void close() throws Exception {
            client.close();
            server.close();
        }
    }
}
