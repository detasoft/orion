package pro.deta.orion.internal.async;

import pro.deta.orion.internal.OrionProperties;

import java.util.concurrent.Callable;

public class StackTraceCapturingCallable<V> implements Callable<V> {
    private static final boolean disableStackTraceCapturingCallable = OrionProperties.get("orion.StackTraceCapturingCallable", false);

    private final Callable<V> delegate;
    private final AsyncThrowable submissionStack;

    public StackTraceCapturingCallable(Callable<V> delegate) {
        this.delegate = delegate;
        if (!disableStackTraceCapturingCallable)
            submissionStack = new AsyncThrowable();
        else
            submissionStack= null;
    }

    @Override
    public V call() throws Exception {
        try {
            return delegate.call();
        } catch (Throwable e) {
            if (submissionStack != null)
                e.addSuppressed(submissionStack.asyncException());
            throw e;
        }
    }
}