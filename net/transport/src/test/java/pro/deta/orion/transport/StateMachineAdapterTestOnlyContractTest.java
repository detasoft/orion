package pro.deta.orion.transport;

import org.junit.jupiter.api.Test;
import pro.deta.orion.lifecycle.state.StateMachine;
import pro.deta.orion.transport.git.GitNativeTransportStateMachine;
import pro.deta.orion.transport.git.GitSshTransportStateMachine;
import pro.deta.orion.transport.http.JettyHTTPServerStateMachine;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateMachineAdapterTestOnlyContractTest {
    @Test
    void publicTestOnlyAccessorsAreMarkedOnStateMachineAdapters() throws IOException {
        Path root = repositoryRoot();

        assertMethodsMarked(root, "net/git-transport/src/main/java/pro/deta/orion/transport/git/GitNativeTransportStateMachine.java",
                "definition",
                "service",
                "startAction",
                "stopAction",
                "currentState",
                "describe",
                "subscribe",
                "start",
                "stop");
        assertMethodsMarked(root, "net/git-transport/src/main/java/pro/deta/orion/transport/git/GitSshTransportStateMachine.java",
                "startAction",
                "stopAction",
                "currentState",
                "start",
                "stop");
        assertMethodsMarked(root, "net/http-core/src/main/java/pro/deta/orion/transport/http/JettyHTTPServerStateMachine.java",
                "startAction",
                "stopAction",
                "currentState",
                "start",
                "stop");
        assertMethodsMarked(root, "net/transport/src/main/java/pro/deta/orion/transport/TransportLifecycleStateMachine.java",
                "gitNativeTransport",
                "gitSshTransport",
                "jettyHttpTransport",
                "definition",
                "currentState");
    }

    @Test
    void productionStateMachineAccessorsAreNotMarkedTestOnly() throws IOException {
        Path root = repositoryRoot();

        assertMethodNotMarked(root, "net/git-transport/src/main/java/pro/deta/orion/transport/git/GitNativeTransportStateMachine.java", "stateMachine");
        assertMethodNotMarked(root, "net/git-transport/src/main/java/pro/deta/orion/transport/git/GitSshTransportStateMachine.java", "stateMachine");
        assertMethodNotMarked(root, "net/http-core/src/main/java/pro/deta/orion/transport/http/JettyHTTPServerStateMachine.java", "stateMachine");
        assertMethodNotMarked(root, "net/transport/src/main/java/pro/deta/orion/transport/TransportLifecycleStateMachine.java", "aggregateStateMachine");
        assertMethodNotMarked(root, "net/transport/src/main/java/pro/deta/orion/transport/TransportLifecycleStateMachine.java", "stateMachine");
        assertMethodNotMarked(root, "net/transport/src/main/java/pro/deta/orion/transport/TransportLifecycleStateMachine.java", "start");
        assertMethodNotMarked(root, "net/transport/src/main/java/pro/deta/orion/transport/TransportLifecycleStateMachine.java", "stop");
    }

    @Test
    void standaloneChildAdaptersExposeRawStateMachineAsProductionApi() {
        assertPublicRawStateMachineAccessor(GitNativeTransportStateMachine.class);
        assertPublicRawStateMachineAccessor(GitSshTransportStateMachine.class);
        assertPublicRawStateMachineAccessor(JettyHTTPServerStateMachine.class);
    }

    @Test
    void transportAggregateDoesNotExposeRawStateMachineAsPublicApi() {
        boolean exposesRawStateMachine = Arrays.stream(TransportLifecycleStateMachine.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("stateMachine")
                        && Modifier.isPublic(method.getModifiers()));

        assertFalse(exposesRawStateMachine);
    }

    private static void assertPublicRawStateMachineAccessor(Class<?> adapterClass) {
        for (Method method : adapterClass.getDeclaredMethods()) {
            if (method.getName().equals("stateMachine")
                    && method.getReturnType().equals(StateMachine.class)
                    && Modifier.isPublic(method.getModifiers())) {
                return;
            }
        }
        throw new AssertionError(adapterClass.getName()
                + "#stateMachine must expose raw StateMachine as standalone production API");
    }

    private static void assertMethodsMarked(Path root, String relativePath, String... methodNames) throws IOException {
        for (String methodName : methodNames) {
            assertMethodMarked(root, relativePath, methodName);
        }
    }

    private static void assertMethodMarked(Path root, String relativePath, String methodName) throws IOException {
        String source = Files.readString(root.resolve(relativePath));

        assertTrue(testOnlyPublicMethodPattern(methodName).matcher(source).find(),
                relativePath + "#" + methodName + " must be marked with @TestOnly");
    }

    private static void assertMethodNotMarked(Path root, String relativePath, String methodName) throws IOException {
        String source = Files.readString(root.resolve(relativePath));

        assertFalse(testOnlyPublicMethodPattern(methodName).matcher(source).find(),
                relativePath + "#" + methodName + " must remain production API");
    }

    private static Pattern testOnlyPublicMethodPattern(String methodName) {
        return Pattern.compile("@TestOnly\\s+public\\s+[^;{=]+\\s+" + Pattern.quote(methodName) + "\\s*\\(");
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve(".root")) && Files.exists(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root");
    }
}
