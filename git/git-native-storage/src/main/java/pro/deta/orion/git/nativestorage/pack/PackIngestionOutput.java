package pro.deta.orion.git.nativestorage.pack;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.net.io.BufferedByteOutput;

import java.io.IOException;
import java.util.Objects;

/**
 * Adapts native pack ingestion to buffered transport output while retaining explicit completion state.
 */
public final class PackIngestionOutput implements BufferedByteOutput, AutoCloseable {
    private final PackIngestionSession session;
    private PackIngestionResult result = new PackIngestionResult.NeedInput();

    public PackIngestionOutput(PackIngestionSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    @Override
    public void write(ByteBuf buffer) throws IOException {
        Objects.requireNonNull(buffer, "buffer");
        if (!(result instanceof PackIngestionResult.NeedInput)) {
            throw new IOException("Native pack ingestion received bytes after completion");
        }
        result = session.accept(buffer);
        requireSuccess();
    }

    @Override
    public void flush() {
    }

    public boolean completed() {
        return result instanceof PackIngestionResult.Complete;
    }

    public PackIngestionResult.Complete complete() throws IOException {
        if (result instanceof PackIngestionResult.NeedInput) {
            result = session.endOfInput();
        }
        requireSuccess();
        if (result instanceof PackIngestionResult.Complete complete) {
            return complete;
        }
        throw new IncompleteException("Native pack ingestion is incomplete");
    }

    private void requireSuccess() throws IOException {
        if (result instanceof PackIngestionResult.Failed failed) {
            throw new IOException(
                    "Native pack ingestion failed: " + failed.failure().getMessage(),
                    failed.failure());
        }
    }

    @Override
    public void close() {
        session.close();
    }

    public static final class IncompleteException extends IOException {
        private IncompleteException(String message) {
            super(message);
        }
    }
}
