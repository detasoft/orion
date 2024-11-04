package pro.deta.orion.util;

import org.junit.jupiter.api.Test;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.internal.OrionThreadFactory;

import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

public class OrionExecutorTest {
    @Test
    public void testSimpleRunnable(){
        try (OrionExecutor executor = new OrionExecutor(5, new OrionThreadFactory())) {
            Future<Object> result = executor.submit(() -> {
                throw new RuntimeException("TestException");
            });
            try {
                Object values = result.get();
            } catch (Throwable t) {
                Throwable k = t.getCause();
                assertThat(k.getSuppressed()).hasSizeGreaterThanOrEqualTo(1).describedAs("Suppressed exceptions are missing: check StackTraceCapturingCallable");
                assertThat(k.getSuppressed()[0]).isNotNull().describedAs("The exception thrown is missing, check StackTraceCapturingCallable");
                return;
            }
            fail("No exception been thrown.");
        }
    }
}
