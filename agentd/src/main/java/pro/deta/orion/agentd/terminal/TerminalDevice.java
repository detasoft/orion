package pro.deta.orion.agentd.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Optional;

interface TerminalDevice extends AutoCloseable {
    InputStream input();

    OutputStream output();

    TerminalSize size() throws IOException;

    Optional<TerminalSize> awaitResize(Duration interval) throws IOException, InterruptedException;

    @Override
    void close() throws IOException;
}
