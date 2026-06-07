package pro.deta.orion.transport;

import org.junit.jupiter.api.Test;
import pro.deta.orion.lifecycle.state.StateMachine;
import pro.deta.orion.transport.git.GitNativeTransportStateMachine;
import pro.deta.orion.transport.git.GitSshTransportStateMachine;
import pro.deta.orion.transport.http.JettyHTTPServerStateMachine;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;

class StateMachineAdapterTestOnlyContractTest {
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
        for (Method method : adapterClass.getMethods()) {
            if (method.getName().equals("stateMachine")
                    && method.getReturnType().equals(StateMachine.class)
                    && Modifier.isPublic(method.getModifiers())) {
                return;
            }
        }
        throw new AssertionError(adapterClass.getName()
                + "#stateMachine must expose raw StateMachine as standalone production API");
    }
}
