package pro.deta.orion.net.io;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SocketChannelBufferedByteInputTest {
    @Test
    void readsAndSkipsAcrossCompactedSocketRefills() throws Exception {
        try (SocketPair sockets = SocketPair.open(); SocketChannelBufferedByteInput input =
                new SocketChannelBufferedByteInput(sockets.client(), UnpooledByteBufAllocator.DEFAULT, 4)) {
            writeAscii(sockets.server(), "abcdef");

            assertThat(input.available()).isZero();
            assertThat(input.readUnsignedByte()).isEqualTo('a');
            assertThat(input.readUnsignedByte()).isEqualTo('b');

            input.skipBytes(1);
            ByteBuf copy = input.readCopy(3);
            try {
                assertThat(copy.toString(StandardCharsets.US_ASCII)).isEqualTo("def");
                assertThat(input.available()).isZero();
            } finally {
                copy.release();
            }
        }
    }

    @Test
    void directReadsAndSkipsReuseTheLocalInputBufferWithoutAllocatingCopies() throws Exception {
        CountingByteBufAllocator allocator = new CountingByteBufAllocator();
        try (SocketPair sockets = SocketPair.open(); SocketChannelBufferedByteInput input =
                new SocketChannelBufferedByteInput(sockets.client(), allocator, 4)) {
            assertThat(allocator.allocations()).isEqualTo(1);
            writeAscii(sockets.server(), "abcdef");

            assertThat(input.readUnsignedByte()).isEqualTo('a');
            assertThat(input.readUnsignedByte()).isEqualTo('b');
            input.skipBytes(2);
            assertThat(input.readUnsignedByte()).isEqualTo('e');
            assertThat(input.readUnsignedByte()).isEqualTo('f');

            assertThat(allocator.allocations()).isEqualTo(1);
        }
    }

    @Test
    void readCopyReturnsOwnedBufferIndependentFromInputBuffer() throws Exception {
        try (SocketPair sockets = SocketPair.open(); SocketChannelBufferedByteInput input =
                new SocketChannelBufferedByteInput(sockets.client(), UnpooledByteBufAllocator.DEFAULT, 8)) {
            writeAscii(sockets.server(), "payloadtail");

            ByteBuf copy = input.readCopy(7);
            try {
                copy.setByte(0, 'P');

                assertThat(copy.toString(StandardCharsets.US_ASCII)).isEqualTo("Payload");
                assertThat(input.readUnsignedByte()).isEqualTo('t');
            } finally {
                copy.release();
            }
        }
    }

    @Test
    void reportsEndOfStreamBeforeRequestedBytesAreAvailable() throws Exception {
        try (SocketPair sockets = SocketPair.open(); SocketChannelBufferedByteInput input =
                new SocketChannelBufferedByteInput(sockets.client(), UnpooledByteBufAllocator.DEFAULT, 8)) {
            writeAscii(sockets.server(), "abc");
            sockets.server().shutdownOutput();

            assertThatThrownBy(() -> input.readCopy(4)).isInstanceOf(java.io.EOFException.class);
        }
    }

    private static void writeAscii(SocketChannel channel, String value) throws Exception {
        ByteBuffer buffer = StandardCharsets.US_ASCII.encode(value);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    record SocketPair(SocketChannel client, SocketChannel server) implements AutoCloseable {

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
