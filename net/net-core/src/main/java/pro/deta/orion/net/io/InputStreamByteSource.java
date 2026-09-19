package pro.deta.orion.net.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Objects;

final class InputStreamByteSource implements BufferedByteInputV2.Source {
    private final InputStream input;
    private final byte[] bytes = new byte[8192];
    private final ByteBuffer buffer = ByteBuffer.wrap(bytes);

    InputStreamByteSource(InputStream input) {
        this.input = Objects.requireNonNull(input, "input");
    }

    @Override
    public ByteBuffer read() throws IOException {
        int count = input.read(bytes);
        if (count < 0) {
            return null;
        }
        if (count == 0) {
            throw new IOException("Input stream made no read progress");
        }
        return buffer.clear().limit(count);
    }

    @Override
    public void release() {}

    @Override
    public void close() throws IOException {
        input.close();
    }
}
