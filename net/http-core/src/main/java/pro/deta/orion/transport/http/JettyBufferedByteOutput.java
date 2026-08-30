package pro.deta.orion.transport.http;

import io.netty.buffer.ByteBuf;
import jakarta.servlet.ServletOutputStream;
import pro.deta.orion.net.io.BufferedByteOutput;

import java.io.IOException;
import java.util.Objects;

public final class JettyBufferedByteOutput implements BufferedByteOutput {
    private final ServletOutputStream output;

    public JettyBufferedByteOutput(ServletOutputStream output) {
        this.output = Objects.requireNonNull(output, "output");
    }

    @Override
    public void write(ByteBuf buffer) throws IOException {
        Objects.requireNonNull(buffer, "buffer");
        if (!buffer.hasArray()) {
            throw new IllegalArgumentException("buffer must expose a backing array");
        }
        output.write(
                buffer.array(),
                buffer.arrayOffset() + buffer.readerIndex(),
                buffer.readableBytes());
    }

    @Override
    public void flush() throws IOException {
        output.flush();
    }
}
