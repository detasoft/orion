package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import pro.deta.orion.net.io.BufferedByteOutput;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class RecordingBufferedByteOutput implements BufferedByteOutput {
    private final ByteBuf target;
    private final ByteArrayOutputStream bytes;
    private final int scratchCapacity;

    public RecordingBufferedByteOutput() {
        this(null, BufferedByteOutput.DEFAULT_BUFFER_CAPACITY);
    }

    public RecordingBufferedByteOutput(ByteBuf target) {
        this(target, BufferedByteOutput.DEFAULT_BUFFER_CAPACITY);
    }

    public RecordingBufferedByteOutput(int scratchCapacity) {
        this(null, scratchCapacity);
    }

    public RecordingBufferedByteOutput(
            ByteBuf target,
            int scratchCapacity) {
        if (scratchCapacity <= 0) {
            throw new IllegalArgumentException(
                    "scratchCapacity must be positive");
        }
        this.target = target;
        this.bytes = target == null ? new ByteArrayOutputStream() : null;
        this.scratchCapacity = scratchCapacity;
    }

    @Override
    public void write(byte[] source, int offset, int length) {
        Objects.requireNonNull(source, "source");
        if (target == null) {
            bytes.write(source, offset, length);
            return;
        }
        target.writeBytes(source, offset, length);
    }

    @Override
    public void write(ByteBuf buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (target == null) {
            byte[] copied = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), copied);
            bytes.write(copied, 0, copied.length);
            return;
        }
        target.writeBytes(
                buffer,
                buffer.readerIndex(),
                buffer.readableBytes());
    }

    @Override
    public void flush() {
    }

    public byte[] bytes() {
        if (target != null) {
            byte[] copied = new byte[target.readableBytes()];
            target.getBytes(target.readerIndex(), copied);
            return copied;
        }
        return bytes.toByteArray();
    }

    public String ascii() throws IOException {
        return target == null
                ? bytes.toString(StandardCharsets.US_ASCII)
                : target.toString(StandardCharsets.US_ASCII);
    }
}
