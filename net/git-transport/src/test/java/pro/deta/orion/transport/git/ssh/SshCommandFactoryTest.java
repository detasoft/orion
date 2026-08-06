package pro.deta.orion.transport.git.ssh;

import org.apache.sshd.server.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestService;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.internal.OrionThreadFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.channels.ClosedByInterruptException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class SshCommandFactoryTest {

    private OrionExecutor executor;

    @AfterEach
    void shutdown() throws InterruptedException {
        if (executor != null) {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void readKeyTimesOutAndUnblocksThreadWhenClientStalls() throws Exception {
        // Two threads required: one blocked inside readKey(), one free to run the watchdog.
        executor = new OrionExecutor(2, new OrionThreadFactory());
        SshCommandFactory factory = new SshCommandFactory(null, executor, null, null, null, 200);

        InputStream stalling = new InputStream() {
            @Override
            public int read() throws IOException {
                try {
                    Thread.sleep(Long.MAX_VALUE);
                } catch (InterruptedException e) {
                    // Re-set the flag so AbstractInterruptibleChannel.end() detects it and
                    // throws ClosedByInterruptException instead of returning -1 silently.
                    Thread.currentThread().interrupt();
                }
                return -1;
            }
        };

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interruptCleared = new AtomicBoolean(true);

        Future<?> task = executor.submit(() -> {
            try {
                factory.readKey(stalling);
            } catch (IOException e) {
                thrown.set(e);
            } finally {
                interruptCleared.set(!Thread.currentThread().isInterrupted());
            }
        });

        assertDoesNotThrow(() -> task.get(3, TimeUnit.SECONDS),
                "readKey() must unblock when the watchdog fires");
        assertInstanceOf(ClosedByInterruptException.class, thrown.get(),
                "watchdog must interrupt the blocked read");
        assertTrue(interruptCleared.get(),
                "readKey() must clear the interrupt flag before returning so the thread is reusable");
    }

    @Test
    void readKeyReturnsFullKeyWhenStreamCompletesNormally() throws Exception {
        executor = new OrionExecutor(2, new OrionThreadFactory());
        SshCommandFactory factory = new SshCommandFactory(null, executor, null, null, null, 30_000);

        String key = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAAB test-key";
        String result = factory.readKey(new ByteArrayInputStream(key.getBytes(StandardCharsets.US_ASCII)));

        assertEquals(key, result);
    }

    @Test
    void readKeyDoesNotSetInterruptFlagOnNormalCompletion() throws Exception {
        executor = new OrionExecutor(2, new OrionThreadFactory());
        SshCommandFactory factory = new SshCommandFactory(null, executor, null, null, null, 30_000);

        factory.readKey(new ByteArrayInputStream(new byte[0]));

        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void nativeInitialRequestDataParsesQuotedRepositoryAndProtocol() {
        InitialRequestData data = SshCommandFactory.initialRequestData(
                "git-upload-pack '/team/project.git'",
                environment(Map.of("GIT_PROTOCOL", "version=2")));

        assertEquals(InitialRequestService.UPLOAD_PACK, data.getService());
        assertEquals("team/project", data.getRepositoryPath());
        assertNull(data.getHost());
        assertEquals(Map.of("version", "2"), data.getParameters());
        assertEquals(
                InitialRequestData.ProtocolVersion.V2,
                data.getProtocolVersion().orElseThrow());
    }

    @Test
    void nativeInitialRequestDataRejectsPathTraversal() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SshCommandFactory.initialRequestData(
                        "git-upload-pack '/../outside.git'",
                        environment(Map.of())));
    }

    private static Environment environment(Map<String, String> values) {
        return (Environment) Proxy.newProxyInstance(
                Environment.class.getClassLoader(),
                new Class<?>[]{Environment.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getEnv" -> values;
                    case "toString" -> "Environment" + values;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(
                            method.toString());
                });
    }
}
