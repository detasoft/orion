package pro.deta.orion.transport;

import org.junit.jupiter.api.Test;
import pro.deta.orion.transport.git.GitNativeTransportService;
import pro.deta.orion.transport.git.GitSshTransportService;
import pro.deta.orion.transport.http.JettyHTTPServer;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Transport services intentionally expose plain lifecycle hooks instead of application stage results.
 * State-machine adapters ignore action return values, so call sites would keep compiling if a transport
 * service started returning the old stage result contract again.
 */
class TransportServiceLifecycleContractTest {
    @Test
    void transportServicesDoNotExposeApplicationStageResults() throws Exception {
        for (Class<?> serviceClass : transportServices()) {
            assertLifecycleMethodReturnsVoid(serviceClass, "onStart");
            assertLifecycleMethodReturnsVoid(serviceClass, "onStop");
        }
    }

    private static List<Class<?>> transportServices() {
        return List.of(
                GitNativeTransportService.class,
                GitSshTransportService.class,
                JettyHTTPServer.class);
    }

    private static void assertLifecycleMethodReturnsVoid(Class<?> serviceClass, String methodName) throws Exception {
        Method method = serviceClass.getMethod(methodName);

        assertEquals(void.class, method.getReturnType(), serviceClass.getSimpleName() + "." + methodName);
    }
}
