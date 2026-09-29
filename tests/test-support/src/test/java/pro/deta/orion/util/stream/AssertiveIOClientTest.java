package pro.deta.orion.util.stream;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssertiveIOClientTest {

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
            SoftAssertions assertions = new SoftAssertions();
            AssertiveIOClient replay = new AssertiveIOClient("S:Hello\\0A\n", assertions);

            assertDoesNotThrow(() -> replay.accept(new ClientIO(send, receive)));

            AssertionError mismatch = assertThrows(AssertionError.class, assertions::assertAll);
            assertTrue(mismatch.getMessage().contains("Hello"));
            assertTrue(mismatch.getMessage().contains("Hel"));
            assertEquals(-1, sent.read());
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
            SoftAssertions assertions = new SoftAssertions();
            AssertiveIOClient replay = new AssertiveIOClient("S:Hello\\0A\n", assertions);

            replay.accept(new ClientIO(send, receive));

            assertions.assertAll();
            assertEquals(-1, sent.read());
        }
    }
}
