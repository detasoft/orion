package pro.deta.orion.net.io;

import io.netty.buffer.ByteBuf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Objects;

public final class SocketChannelByteBufWrite implements BufferedByteOutput {
    private final SocketChannel channel;

    public SocketChannelByteBufWrite(SocketChannel channel) {
        this.channel = Objects.requireNonNull(channel, "channel");
        if (!channel.isBlocking()) {
            throw new IllegalArgumentException("channel must be blocking");
        }
    }

    @Override
    public void write(ByteBuf buffer) throws IOException {
        Objects.requireNonNull(buffer, "buffer");
        ByteBuffer source = buffer.nioBuffer(
                buffer.readerIndex(),
                buffer.readableBytes());
        while (source.hasRemaining()) {
            channel.write(source);
        }
    }

    @Override
    public void flush() {
    }
}
