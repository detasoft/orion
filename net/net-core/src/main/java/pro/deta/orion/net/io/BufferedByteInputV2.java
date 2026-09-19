package pro.deta.orion.net.io;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

public final class BufferedByteInputV2 implements Closeable {
    public interface Source extends Closeable {
        ByteBuffer read() throws IOException;

        void release();
    }

    private final Source source;
    private ByteBuffer current;
    private boolean eof;
    private boolean closed;

    public BufferedByteInputV2(InputStream input) {
        this(new InputStreamByteSource(input));
    }

    public BufferedByteInputV2(Source source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    public ByteBuffer buffer() throws IOException {
        requireOpen();
        while (current == null || !current.hasRemaining()) {
            if (eof) {
                return null;
            }
            releaseCurrent();
            current = source.read();
            if (current == null) {
                eof = true;
                return null;
            }
        }
        return current;
    }

    public int readUnsignedByte() throws IOException {
        return Byte.toUnsignedInt(requireBuffer().get());
    }

    public int readInt() throws IOException {
        ByteBuffer buffer = requireBuffer();
        if (buffer.remaining() >= Integer.BYTES) {
            int value = buffer.getInt();
            return buffer.order() == ByteOrder.BIG_ENDIAN ? value : Integer.reverseBytes(value);
        }
        return readUnsignedByte() << 24 | readUnsignedByte() << 16
                | readUnsignedByte() << 8 | readUnsignedByte();
    }

    public byte[] readBytes(int length) throws IOException {
        if (length < 0) {
            throw new IllegalArgumentException("length must be non-negative");
        }
        requireOpen();
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            ByteBuffer buffer = requireBuffer();
            int count = Math.min(length - offset, buffer.remaining());
            buffer.get(bytes, offset, count);
            offset += count;
        }
        return bytes;
    }

    public InputStream newInputStream() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                ByteBuffer buffer = buffer();
                return buffer == null ? -1 : Byte.toUnsignedInt(buffer.get());
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, bytes.length);
                requireOpen();
                if (length == 0) {
                    return 0;
                }
                ByteBuffer buffer = buffer();
                if (buffer == null) {
                    return -1;
                }
                int count = Math.min(length, buffer.remaining());
                buffer.get(bytes, offset, count);
                return count;
            }

            @Override
            public long skip(long length) throws IOException {
                requireOpen();
                long skipped = 0;
                while (skipped < length) {
                    ByteBuffer buffer = buffer();
                    if (buffer == null) {
                        break;
                    }
                    int count = (int) Math.min(length - skipped, buffer.remaining());
                    buffer.position(buffer.position() + count);
                    skipped += count;
                }
                return skipped;
            }

            @Override
            public int available() throws IOException {
                requireOpen();
                return current == null ? 0 : current.remaining();
            }
        };
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            try {
                releaseCurrent();
            } finally {
                source.close();
            }
        }
    }

    private ByteBuffer requireBuffer() throws IOException {
        ByteBuffer buffer = buffer();
        if (buffer == null) {
            throw new EOFException("Input reached end of stream");
        }
        return buffer;
    }

    private void releaseCurrent() {
        if (current != null) {
            current = null;
            source.release();
        }
    }

    private void requireOpen() throws IOException {
        if (closed) {
            throw new IOException("Input is closed");
        }
    }
}
