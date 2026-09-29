package pro.deta.orion.util.stream;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pro.deta.orion.util.stream.IOTestStreamUtils.testPipeScenario;

class IOTestStreamUtilsTest {

    @Test
    void propagatesClientAssertionAndClosesBothPipeDirections() {
        AssertionError failure = new AssertionError("Wrong response");
        AtomicReference<ClientIO> clientStreams = new AtomicReference<>();
        AtomicReference<ServerIO> serverStreams = new AtomicReference<>();

        AssertionError observed = assertThrows(AssertionError.class, () -> testPipeScenario(client -> {
            clientStreams.set(client);
            throw failure;
        }, serverStreams::set));

        assertSame(failure, observed);
        assertThrows(IOException.class, () -> clientStreams.get().getSend().write(1));
        assertThrows(IOException.class, () -> clientStreams.get().getReceive().read());
        assertThrows(IOException.class, () -> serverStreams.get().getSend().write(1));
        assertThrows(IOException.class, () -> serverStreams.get().getReceive().read());
    }

    @Test
    void propagatesClientIoFailureWithItsOriginalCause() {
        IOException failure = new IOException("Client read failed");

        IllegalStateException observed = assertThrows(IllegalStateException.class,
                () -> testPipeScenario(client -> {
                    throw failure;
                }, server -> { }));

        assertSame(failure, observed.getCause());
    }

    @Test
    void propagatesClientRuntimeFailureUnchanged() {
        IllegalArgumentException failure = new IllegalArgumentException("Invalid response");

        IllegalArgumentException observed = assertThrows(IllegalArgumentException.class,
                () -> testPipeScenario(client -> {
                    throw failure;
                }, server -> { }));

        assertSame(failure, observed);
    }

    @Test
    void serverIoFailureReleasesTheReadingClientBeforeReturning() throws IOException, InterruptedException {
        IOException failure = new IOException("Server failed");
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean clientFinished = new AtomicBoolean();
        AtomicReference<ServerIO> serverStreams = new AtomicReference<>();

        try {
            IllegalStateException observed = assertThrows(IllegalStateException.class,
                    () -> testPipeScenario(client -> {
                        reading.countDown();
                        try {
                            client.getReceive().read();
                        } finally {
                            clientFinished.set(true);
                            finished.countDown();
                        }
                    }, server -> {
                        serverStreams.set(server);
                        await(reading);
                        throw failure;
                    }));

            assertSame(failure, observed.getCause());
            assertTrue(clientFinished.get(), "Client must leave its blocked read before the helper returns");
        } finally {
            if (serverStreams.get() != null) {
                serverStreams.get().getSend().close();
                assertTrue(finished.await(5, TimeUnit.SECONDS), "Test cleanup did not release the client");
            }
        }
    }

    @Test
    void clientFailureIsNotReplacedByTheServersResultingPipeFailure() {
        AssertionError failure = new AssertionError("Client failed after sending");

        AssertionError observed = assertThrows(AssertionError.class, () -> testPipeScenario(client -> {
            client.getSend().write(1);
            throw failure;
        }, server -> {
            server.getReceive().read();
            server.getReceive().read();
        }));

        assertSame(failure, observed);
    }

    @Test
    void propagatesServerRuntimeFailureUnchanged() {
        IllegalArgumentException failure = new IllegalArgumentException("Server failed");

        IllegalArgumentException observed = assertThrows(IllegalArgumentException.class,
                () -> testPipeScenario(client -> { }, server -> {
                    throw failure;
                }));

        assertSame(failure, observed);
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Client did not enter its read");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException(failure);
        }
    }
}
