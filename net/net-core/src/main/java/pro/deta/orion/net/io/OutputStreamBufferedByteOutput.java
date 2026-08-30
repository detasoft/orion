package pro.deta.orion.net.io;

import io.netty.buffer.ByteBuf;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

public final class OutputStreamBufferedByteOutput implements BufferedByteOutput {
    private final OutputStream output;

    public OutputStreamBufferedByteOutput(OutputStream output) {
        this.output = Objects.requireNonNull(output, "output");
    }

    @Override
    public void write(ByteBuf buffer) throws IOException {
        Objects.requireNonNull(buffer, "buffer");
        buffer.getBytes(
                buffer.readerIndex(),
                output,
                buffer.readableBytes());
    }

    @Override
    public void flush() throws IOException {
        output.flush();
    }
}
