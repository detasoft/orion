package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import pro.deta.orion.net.io.BufferedByteOutput;

import java.util.Objects;
import java.util.function.Consumer;

public final class SubmittedByteBufOutput implements BufferedByteOutput {
    private final ByteBuf scratch;
    private final Consumer<ByteBuf> submitted;

    public SubmittedByteBufOutput(
            ByteBuf scratch,
            Consumer<ByteBuf> submitted) {
        this.scratch = Objects.requireNonNull(scratch, "scratch");
        this.submitted = Objects.requireNonNull(submitted, "submitted");
    }

    @Override
    public void write(byte[] source, int offset, int length) {
        byte[] copied = new byte[length];
        System.arraycopy(source, offset, copied, 0, length);
        submitted.accept(Unpooled.wrappedBuffer(copied));
    }

    @Override
    public void write(ByteBuf buffer) {
        if (!buffer.isReadable()) {
            return;
        }
        submitted.accept(buffer.copy(
                buffer.readerIndex(),
                buffer.readableBytes()));
    }

    @Override
    public void flush() {
    }
}
