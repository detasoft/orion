package pro.deta.orion.util;

import org.slf4j.MDC;

import java.util.Map;

/** Restores the caller's logging context when a synchronous operation completes. */
public final class LogScope implements AutoCloseable {
    private final Map<String, String> previous = MDC.getCopyOfContextMap();

    private LogScope(String taskId, String userId) {
        set("taskId", taskId);
        set("userId", userId);
    }

    public static LogScope task(String taskId) {
        return new LogScope(taskId, MDC.get("userId"));
    }

    public static LogScope user(String userId) {
        return new LogScope(null, userId);
    }

    private static void set(String key, String value) {
        if (value == null || value.isBlank()) MDC.remove(key);
        else MDC.put(key, value);
    }

    @Override
    public void close() {
        if (previous == null) MDC.clear();
        else MDC.setContextMap(previous);
    }
}
