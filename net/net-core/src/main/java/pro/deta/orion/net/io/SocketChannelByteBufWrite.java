package pro.deta.orion.net.io;

import io.netty.buffer.ByteBuf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class SocketChannelByteBufWrite {
    private final SocketChannel channel;

    public SocketChannelByteBufWrite(SocketChannel channel) {
        this.channel = Objects.requireNonNull(channel, "channel");
    }

    public CompletionStage<Void> write(ByteBuf ownedBuffer) {
        Objects.requireNonNull(ownedBuffer, "ownedBuffer");
        ByteBuffer source = ownedBuffer.nioBuffer(
                ownedBuffer.readerIndex(),
                ownedBuffer.readableBytes());
        try {
            while (source.hasRemaining()) {
                channel.write(source);
            }
            return CompletableFuture.completedFuture(null);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
