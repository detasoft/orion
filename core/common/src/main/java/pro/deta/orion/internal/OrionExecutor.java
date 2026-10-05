package pro.deta.orion.internal;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.internal.async.StackTraceCapturingCallable;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter;

import java.util.concurrent.*;

@Singleton
@Slf4j
public class OrionExecutor extends ScheduledThreadPoolExecutor implements ServiceLifecycleStateMachineAdapter.ServiceLifecycle {
    @Inject
    public OrionExecutor(BootstrapConfiguration orionConfiguration, OrionThreadFactory orionThreadFactory) {
        this(orionConfiguration.getBootstrap().getThreadPoolSize(), orionThreadFactory);
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
    public void onStart() {
        // The executor is active immediately after construction; the lifecycle state machine records that fact.
    }

    @Override
    public void onStop() {
        shutdown();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean isRunning() {
        return !isShutdown();
    }

}
