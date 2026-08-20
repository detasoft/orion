package pro.deta.orion.net.io;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SocketChannelByteBufWriteTest {
    @Test
    void writesReadableBytesToSocketChannel() throws Exception {
        try (SocketPair sockets = SocketPair.open()) {
            SocketChannelByteBufWrite writer = new SocketChannelByteBufWrite(sockets.client());
            ByteBuf buffer = Unpooled.copiedBuffer("hello", StandardCharsets.US_ASCII);
            try {
                writer.write(buffer).toCompletableFuture().join();

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
