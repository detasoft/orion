package pro.deta.orion.git.nativestorage.pack;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import pro.deta.orion.net.io.BufferedByteOutput;

import java.io.IOException;
import java.util.Objects;

public interface NativePackProducer extends AutoCloseable {
    int DEFAULT_OUTPUT_BUFFER_SIZE = 80 * 1024;

    Result produce(ByteBuf destination);

    default Result produce(BufferedByteOutput destination) throws IOException {
        Objects.requireNonNull(destination, "destination");
        ByteBuf buffer = Unpooled.buffer(DEFAULT_OUTPUT_BUFFER_SIZE, DEFAULT_OUTPUT_BUFFER_SIZE);
        try {
            Result result = produce(buffer);
            if (!buffer.isReadable() && result == Result.MORE) {
                throw new IllegalStateException("Native pack producer made no progress");
            }
            destination.write(buffer);
            return result;
        } finally {
            buffer.release();
        }
    }

    @Override
    void close();

    enum Result {
        MORE, COMPLETED
    }
}
