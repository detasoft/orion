package pro.deta.orion.util.stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pro.deta.orion.util.stream.IOTestStreamUtils.testPipeScenario;

class AssertiveIOClientTest {

    @ParameterizedTest
    @ValueSource(strings = {"Jello\n", "", "Hel"})
    void scenarioRejectsDifferentOrIncompleteServerResponse(String response) {
        AssertiveIOClient replay = new AssertiveIOClient("S:Hello\\0A\n");

        AssertionError mismatch = assertThrows(AssertionError.class, () -> testPipeScenario(replay, server -> {
            server.getSend().write(response.getBytes(StandardCharsets.UTF_8));
            server.getSend().close();
        }));

        assertTrue(mismatch.getMessage().contains("Hello"));
    }

    @Test
    void truncatedResponseStopsAtEofAndComparesReceivedBytes() throws IOException {
        try (PipedInputStream receive = new PipedInputStream() {
            private boolean eofSeen;

            @Override
            public synchronized int read(byte[] bytes, int offset, int length) throws IOException {
                if (eofSeen) {
                    throw new IOException("Replay read again after EOF");
                }
                int count = super.read(bytes, offset, length);
                eofSeen = count == -1;
                return count;
            }
        }; PipedOutputStream source = new PipedOutputStream(receive);
             PipedInputStream sent = new PipedInputStream();
             PipedOutputStream send = new PipedOutputStream(sent)) {
            source.write("Hel".getBytes(StandardCharsets.UTF_8));
            source.close();
            AssertiveIOClient replay = new AssertiveIOClient("S:Hello\\0A\n");

            AssertionError mismatch = assertThrows(AssertionError.class,
                    () -> replay.accept(new ClientIO(send, receive)));
            assertTrue(mismatch.getMessage().contains("Hello"));
            assertTrue(mismatch.getMessage().contains("Hel"));
        }
    }

    @Test
    void completeResponseStillMatchesTheTranscript() throws IOException {
        try (PipedInputStream receive = new PipedInputStream();
             PipedOutputStream source = new PipedOutputStream(receive);
             PipedInputStream sent = new PipedInputStream();
             PipedOutputStream send = new PipedOutputStream(sent)) {
            source.write("Hello\n".getBytes(StandardCharsets.UTF_8));
            source.close();
            AssertiveIOClient replay = new AssertiveIOClient("S:Hello\\0A\n");

            replay.accept(new ClientIO(send, receive));

            assertEquals(-1, sent.read());
        }
    }
}
