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
        if (buffer.hasArray()) {
            output.write(
                    buffer.array(),
                    buffer.arrayOffset() + buffer.readerIndex(),
                    buffer.readableBytes());
            return;
        }
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
