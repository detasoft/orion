package pro.deta.orion.internal.async;

import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.lifecycle.ApplicationStateListenerRegistrar;
import pro.deta.orion.lifecycle.OrionApplicationLifecycle;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.lifecycle.data.OrionStageCallResultFuture;
import pro.deta.orion.lifecycle.listener.RegisteredListener;
import pro.deta.orion.util.OrionUtils;

import java.util.Arrays;
import java.util.Set;

public final class AsyncThrowable extends Throwable {
    private static final Set<String> skipClasses = Set.of(
            OrionApplicationLifecycle.class.getName(),
            Thread.class.getName(),
            ApplicationStateListenerRegistrar.class.getName(),
            RegisteredListener.class.getName(),
            OrionStageCallResultFuture.class.getName(),
            OrionStageCallResult.class.getName(),
            OrionUtils.class.getName(),
            AsyncThrowable.class.getName(),
            StackTraceCapturingCallable.class.getName(),
            OrionExecutor.class.getName()
    );

    public AsyncThrowable() {
        super();
    }

    public AsyncThrowable(AsyncThrowable originalStackTrace) {
        super("--- async stack trace (orion) ---", null, true, true);
        setStackTrace(originalStackTrace.initiatorTrace());
    }

    public Throwable asyncException() {
        return new AsyncThrowable(this);
    }

    private StackTraceElement[] initiatorTrace() {
        StackTraceElement[] elements = getStackTrace();
        int i = findIndexOfFirstNonSkipClass(elements);
        if (i >= 0)
            return Arrays.copyOfRange(elements, i, elements.length);
        else
            return elements;
    }

    public static int findIndexOfFirstNonSkipClass(StackTraceElement[] stackTraceElements) {
        for (int i = 0; i < stackTraceElements.length; i++) {
            if (!skipClasses.contains(stackTraceElements[i].getClassName())) {
                return i;
            }
        }
        return -1;
    }

}