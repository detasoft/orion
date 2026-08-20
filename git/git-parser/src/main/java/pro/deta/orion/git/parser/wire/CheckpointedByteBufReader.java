package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;

import java.util.Objects;

final class CheckpointedByteBufReader implements AutoCloseable {
    private final ByteBuf input;
    private final int startReaderIndex;
    private boolean committed;

    private CheckpointedByteBufReader(ByteBuf input) {
        this.input = Objects.requireNonNull(input, "input");
        startReaderIndex = input.readerIndex();
    }

    static CheckpointedByteBufReader open(ByteBuf input) {
        return new CheckpointedByteBufReader(input);
    }

    int readableBytes() {
        return input.readableBytes();
    }

    boolean isReadable() {
        return input.isReadable();
    }

    int readerIndex() {
        return input.readerIndex();
    }

    int readUnsignedByte() {
        return input.readUnsignedByte();
    }

    int readInt() {
        return input.readInt();
    }

    void skipBytes(int length) {
        input.skipBytes(length);
    }

    ByteBuf readRetainedSlice(int length) {
        return input.readRetainedSlice(length);
    }

    void commit() {
        committed = true;
    }

    @Override
    public void close() {
        if (!committed) {
            input.readerIndex(startReaderIndex);
        }
    }
}
