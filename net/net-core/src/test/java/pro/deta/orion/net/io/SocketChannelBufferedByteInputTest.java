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
    void readsIntoTargetAcrossCompactedSocketRefills() throws Exception {
        try (SocketPair sockets = SocketPair.open(); SocketChannelBufferedByteInput input =
                new SocketChannelBufferedByteInput(sockets.client(), UnpooledByteBufAllocator.DEFAULT, 4)) {
            writeAscii(sockets.server(), "abcdef");

            assertThat(input.available()).isZero();
            assertThat(input.readUnsignedByte()).isEqualTo('a');
            assertThat(input.readUnsignedByte()).isEqualTo('b');

            ByteBuf target = UnpooledByteBufAllocator.DEFAULT.buffer(3, 3);
            try {
                assertThat(input.readInto(target, 1)).isEqualTo(1);
                assertThat(input.readInto(target, 2)).isEqualTo(1);
                assertThat(input.readInto(target, 2)).isEqualTo(1);
                assertThat(target.toString(StandardCharsets.US_ASCII)).isEqualTo("cde");
                assertThat(input.readUnsignedByte()).isEqualTo('f');
                assertThat(input.available()).isZero();
            } finally {
                target.release();
            }
        }
    }

    @Test
    void directReadsAndReadIntoReuseTheLocalInputBufferWithoutAllocatingCopies() throws Exception {
        CountingByteBufAllocator allocator = new CountingByteBufAllocator();
        try (SocketPair sockets = SocketPair.open(); SocketChannelBufferedByteInput input =
                new SocketChannelBufferedByteInput(sockets.client(), allocator, 4)) {
            assertThat(allocator.allocations()).isEqualTo(1);
            writeAscii(sockets.server(), "abcdef");

            assertThat(input.readUnsignedByte()).isEqualTo('a');
            assertThat(input.readUnsignedByte()).isEqualTo('b');
            ByteBuf target = UnpooledByteBufAllocator.DEFAULT.buffer(2, 2);
            try {
                assertThat(input.readInto(target, 2)).isEqualTo(2);
                assertThat(target.toString(StandardCharsets.US_ASCII)).isEqualTo("cd");
            } finally {
                target.release();
            }
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

            ByteBuf copy = input.readCopy(7, UnpooledByteBufAllocator.DEFAULT);
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
    void readIntoHonorsMaxLengthAndLeavesRemainingBytesBuffered() throws Exception {
        try (SocketPair sockets = SocketPair.open(); SocketChannelBufferedByteInput input =
                new SocketChannelBufferedByteInput(sockets.client(), UnpooledByteBufAllocator.DEFAULT, 8)) {
            writeAscii(sockets.server(), "abcdef");
            ByteBuf target = UnpooledByteBufAllocator.DEFAULT.buffer(6, 6);
            try {
                assertThat(input.readInto(target, 3)).isEqualTo(3);

                assertThat(target.toString(StandardCharsets.US_ASCII)).isEqualTo("abc");
                assertThat(input.readUnsignedByte()).isEqualTo('d');
            } finally {
                target.release();
            }
        }
    }

    @Test
    void reportsEndOfStreamBeforeRequestedBytesAreAvailable() throws Exception {
        try (SocketPair sockets = SocketPair.open(); SocketChannelBufferedByteInput input =
                new SocketChannelBufferedByteInput(sockets.client(), UnpooledByteBufAllocator.DEFAULT, 8)) {
            writeAscii(sockets.server(), "abc");
            sockets.server().shutdownOutput();

            assertThatThrownBy(() -> input.readCopy(4, UnpooledByteBufAllocator.DEFAULT))
                    .isInstanceOf(java.io.EOFException.class);
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
