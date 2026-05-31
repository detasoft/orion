package pro.deta.orion.transport;

import org.junit.jupiter.api.Test;
import pro.deta.orion.config.schema.GitTransportConfig;
import pro.deta.orion.config.schema.OrionConfiguration;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrionTransportModuleTest {
    @Test
    void transportModuleRegistersTransportAggregateWithoutDirectNativeServiceBinding() {
        List<String> parameterTypes = new ArrayList<>();
        for (Method method : OrionTransportModule.class.getDeclaredMethods()) {
            for (Type parameterType : method.getGenericParameterTypes()) {
                parameterTypes.add(parameterType.getTypeName());
            }
        }

        assertTrue(containsType(parameterTypes, TransportLifecycleStateMachine.class.getName()));
        assertFalse(containsType(parameterTypes, "GitNativeTransportStateMachine"));
        assertFalse(containsType(parameterTypes, "GitNativeTransportService"));
    }

    @Test
    void transportModuleProvidesGitTransportConfigFromRuntimeConfiguration() {
        OrionConfiguration configuration = new OrionConfiguration();
        configuration.getTransport().getGit().setEnabled(true);
        configuration.getTransport().getGit().setPort(19418);

        GitTransportConfig gitTransportConfig = OrionTransportModule.gitTransportConfig(configuration);

        assertTrue(gitTransportConfig.isEnabled());
        assertEquals(19418, gitTransportConfig.getPort());
    }

    private static boolean containsType(List<String> parameterTypes, String value) {
        for (String parameterType : parameterTypes) {
            if (parameterType.contains(value)) {
                return true;
            }
        }
        return false;
    }
}
