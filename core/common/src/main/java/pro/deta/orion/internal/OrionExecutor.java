package pro.deta.orion.internal;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.internal.async.StackTraceCapturingCallable;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.lifecycle.ApplicationStateListenerRegistrar;
import pro.deta.orion.lifecycle.OrionApplicationStageEventListener;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.util.OrionUtils;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;

@Singleton
@Slf4j
public class OrionExecutor extends ScheduledThreadPoolExecutor implements OrionApplicationStageEventListener {
    private final List<Thread> dedicatedThreads = new LinkedList<>();

    @Inject
    public OrionExecutor(OrionConfiguration orionConfiguration, OrionThreadFactory orionThreadFactory) {
        this(orionConfiguration.getThreadPoolSize(), orionThreadFactory);
    }

    public OrionExecutor(int threadPoolSize, OrionThreadFactory orionThreadFactory) {
        super(threadPoolSize);
        setThreadFactory(orionThreadFactory);
        if (threadPoolSize < 2)
            throw new IllegalStateException("Number of threads should be at least 2 (one goes to GitNativaTransportService)");
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable,
                                           long delay,
                                           TimeUnit unit) {
        return super.schedule(new StackTraceCapturingCallable(callable), delay, unit);
    }

    @Override
    public void registerToStage(ApplicationStateListenerRegistrar registrar) {
        registrar.register(ApplicationState.STOPPING, this::onStop).priority(99); // last one to
    }

    public OrionStageCallResult onStop() {
        for (Thread t: dedicatedThreads) {
            t.interrupt();

        }
        shutdown();
        return null;
    }

    public static Map<String, String> dumpThreadContextParams(Thread t) {
        Map<String, String> threadContextMap = new HashMap<>();
        try {
            Field threadLocalsField = Thread.class.getDeclaredField("threadLocals");
            threadLocalsField.setAccessible(true);
            Field inheritableThreadLocalsField = Thread.class.getDeclaredField("inheritableThreadLocals");
            inheritableThreadLocalsField.setAccessible(true);
            appendThreadContextMap(threadContextMap, threadLocalsField, t);
            appendThreadContextMap(threadContextMap, inheritableThreadLocalsField, t);
        } catch (Throwable ignored) {
        }
        return threadContextMap;
    }

    private static void appendThreadContextMap(Map<String, String> threadContextMap, Field threadLocalsField, Thread t) {
        try {
            Object threadLocals = threadLocalsField.get(t);
            if (threadLocals == null) return;
            Field tableField = threadLocals.getClass().getDeclaredField("table");
            tableField.setAccessible(true);
            Object[] table = (Object[]) tableField.get(threadLocals);

            if (table != null) {
                for (Object entry : table) {
                    if (entry != null) {
                        Field entryValueField = entry.getClass().getDeclaredField("value");
                        entryValueField.setAccessible(true);
                        Object value = entryValueField.get(entry);
//                        if (value instanceof ReadOnlyStringMap) {
//                            threadContextMap.putAll(((ReadOnlyStringMap)value).toMap());
//                            return;
//                        }
                    }
                }
            }
        } catch (IllegalAccessException ignored) {
        } catch (NoSuchFieldException ignored) {
        }
    }

    public Thread newDedicatedThread(Runnable r) {
        Thread thread = getThreadFactory().newThread(() -> {
            OrionUtils.wrapRunnableInThreadName(OrionUtils.initiatorOf(r.getClass()), () -> r.run());
        });
        return thread;
    }

}
