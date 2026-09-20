package pro.deta.orion.git.parser.v2.read;

import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

public final class DeltaByteSource implements BufferedByteInputV2.Source {
    private final BufferedByteInputV2 instructions;
    private final byte[] base;
    private final long size;
    private long remaining;
    private int literalRemaining;

    public DeltaByteSource(BufferedByteInputV2 instructions, byte[] base) throws IOException {
        this.instructions = Objects.requireNonNull(instructions, "instructions");
        this.base = Objects.requireNonNull(base, "base");
        if (readSize() != base.length) {
            throw new IOException("Delta source size does not match base object");
        }
        size = readSize();
        remaining = size;
    }

    public long size() {
        return size;
    }

    @Override
    public ByteBuffer read() throws IOException {
        if (remaining == 0) {
            if (instructions.buffer() != null) {
                throw new IOException("Delta instructions exceed target size");
            }
            return null;
        }
        if (literalRemaining == 0) {
            int opcode = instructions.readUnsignedByte();
            if (opcode == 0) {
                throw new IOException("Invalid zero delta opcode");
            }
            if ((opcode & 128) != 0) {
                long offset = 0;
                int count = 0;
                for (int i = 0; i < 4; i++) {
                    if ((opcode & (1 << i)) != 0) {
                        offset |= (long) instructions.readUnsignedByte() << (8 * i);
                    }
                }
                for (int i = 0; i < 3; i++) {
                    if ((opcode & (16 << i)) != 0) {
                        count |= instructions.readUnsignedByte() << (8 * i);
                    }
                }
                if (count == 0) {
                    count = 0x10000;
                }
                if (offset + count > base.length) {
                    throw new IOException("Delta copy exceeds base object size");
                }
                consume(count);
                return ByteBuffer.wrap(base, (int) offset, count).slice().asReadOnlyBuffer();
            }
            literalRemaining = opcode;
            if (literalRemaining > remaining) {
                throw new IOException("Delta instruction exceeds target size");
            }
        }
        ByteBuffer input = instructions.buffer();
        if (input == null) {
            throw new EOFException("Truncated delta literal");
        }
        int count = Math.min(literalRemaining, input.remaining());
        ByteBuffer literal = input.slice(input.position(), count).asReadOnlyBuffer();
        input.position(input.position() + count);
        literalRemaining -= count;
        consume(count);
        return literal;
    }

    @Override
    public void release() {}

    @Override
    public void close() {}

    private void consume(int count) throws IOException {
        if (count > remaining) {
            throw new IOException("Delta instruction exceeds target size");
        }
        remaining -= count;
    }

    private long readSize() throws IOException {
        long value = 0;
        for (int shift = 0; shift < 63; shift += 7) {
            int next = instructions.readUnsignedByte();
            value |= (long) (next & 127) << shift;
            if ((next & 128) == 0) {
                return value;
            }
        }
        throw new IOException("Delta size overflows a signed long");
    }
}
