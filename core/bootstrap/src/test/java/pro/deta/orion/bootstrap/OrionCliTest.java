package pro.deta.orion.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrionCliTest {

    @Test
    void defaultsToRunningTheServerWhenNoCommandIsProvided() {
        RecordingServerRunner serverRunner = new RecordingServerRunner(0);
        RecordingVerifier verifier = new RecordingVerifier(0);

        int exitCode = new OrionCli(serverRunner, verifier).run(new String[0], newPrintStream(), newPrintStream());

        assertEquals(0, exitCode);
        assertEquals(1, serverRunner.calls);
        assertFalse(verifier.called);
    }

    @Test
    void verifyCommandDelegatesToVerifierWithoutStartingTheServer() {
        RecordingServerRunner serverRunner = new RecordingServerRunner(0);
        RecordingVerifier verifier = new RecordingVerifier(0);

        int exitCode = new OrionCli(serverRunner, verifier).run(
                new String[]{"verify", "--fingerprint", "AB CD", "--signature", "orion.jar.asc"},
                newPrintStream(),
                newPrintStream()
        );

        assertEquals(0, exitCode);
        assertEquals(0, serverRunner.calls);
        assertTrue(verifier.called);
        assertArrayEquals(new String[]{"--fingerprint", "AB CD", "--signature", "orion.jar.asc"}, verifier.arguments);
    }

    @Test
    void unknownCommandReturnsUsageError() {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        int exitCode = new OrionCli(new RecordingServerRunner(0), new RecordingVerifier(0)).run(
                new String[]{"bogus"},
                newPrintStream(),
                new PrintStream(errors, true, StandardCharsets.UTF_8)
        );

        assertEquals(2, exitCode);
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("Unknown command: bogus"));
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("Usage:"));
    }

    private static PrintStream newPrintStream() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    private static class RecordingServerRunner implements OrionCli.ServerRunner {
        private final int exitCode;
        private int calls;

        private RecordingServerRunner(int exitCode) {
            this.exitCode = exitCode;
        }

        @Override
        public int run() {
            calls++;
            return exitCode;
        }
    }

    private static class RecordingVerifier implements OrionCli.Verifier {
        private final int exitCode;
        private boolean called;
        private String[] arguments;

        private RecordingVerifier(int exitCode) {
            this.exitCode = exitCode;
        }

        @Override
        public int verify(String[] arguments, PrintStream output, PrintStream errors) {
            this.called = true;
            this.arguments = arguments;
            return exitCode;
        }
    }
}
