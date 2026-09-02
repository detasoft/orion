package pro.deta.orion.transport.git.ssh;

import org.apache.sshd.server.Environment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestService;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class SshCommandFactoryTest {

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

    @Test
    void receivePackProtocolErrorWritesStackTraceToSidebandErrorChannel()
            throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RuntimeException error = new RuntimeException("boom");
        error.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("Example", "method", "Example.java", 12)
        });

        SshCommandFactory.writeGitProtocolException(
                output,
                "git-receive-pack '/demo.git'",
                error);

        byte[] packet = output.toByteArray();
        assertEquals(3, packet[4]);
        String payload = new String(
                packet,
                5,
                packet.length - 5,
                StandardCharsets.UTF_8);
        assertTrue(payload.contains("java.lang.RuntimeException: boom"));
        assertTrue(payload.contains("at Example.method(Example.java:12)"));
    }

    @Test
    void protocolErrorHelpersDoNotCreateBlockingWireTransport() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/pro/deta/orion/transport/git/ssh/SshCommandFactory.java"));
        String helpers = source.substring(
                source.indexOf("static void writeGitProtocolException("),
                source.indexOf("private static boolean isReceivePack("));

        assertFalse(helpers.contains("new GitBlockingWireTransport("));
    }

    @Test
    void receivePackProtocolErrorSplitsLargeStackTrace() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RuntimeException error = new RuntimeException("boom");
        StackTraceElement[] stackTrace = new StackTraceElement[2_000];
        for (int index = 0; index < stackTrace.length; index++) {
            stackTrace[index] = new StackTraceElement(
                    "ExampleClass" + index,
                    "method",
                    "Example.java",
                    index + 1);
        }
        error.setStackTrace(stackTrace);

        SshCommandFactory.writeGitProtocolException(
                output,
                "git-receive-pack '/demo.git'",
                error);

        byte[] bytes = output.toByteArray();
        int secondPacketOffset = 0xfff0;
        assertEquals("fff0", new String(
                bytes,
                0,
                4,
                StandardCharsets.US_ASCII));
        assertEquals(3, bytes[4]);
        assertEquals(3, bytes[secondPacketOffset + 4]);
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
