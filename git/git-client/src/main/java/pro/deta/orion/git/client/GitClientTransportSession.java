package pro.deta.orion.git.client;

import pro.deta.orion.net.io.BufferedByteInput;
import pro.deta.orion.net.io.BufferedByteOutput;

import java.io.IOException;

/**
 * One blocking Git service exchange. Implementations apply their configured
 * read and write timeouts and must make {@link #close()} idempotent. Closing
 * must unblock an active input or output operation.
 */
public interface GitClientTransportSession extends AutoCloseable {
    BufferedByteInput input();

    BufferedByteOutput output();

    @Override
    void close() throws IOException;
}
