package pro.deta.orion.test.util;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.event.type.OrionEvent;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.internal.OrionThreadFactory;

/** Provides events, an executor, and owned resources for one test, and closes them after execution. */
public final class OrionTestRuntime implements AfterEachCallback, AutoCloseable {
    private OrionEventManager manager;
    private OrionExecutor executor;
    private final List<AutoCloseable> resources = new ArrayList<>();

    public void own(AutoCloseable resource) {
        resources.add(resource);
    }

    public OrionEventManager events() {
        unstartedEvents();
        if (!manager.isRunning()) manager.onStart();
        return manager;
    }

    public OrionEventManager unstartedEvents() {
        if (manager == null) manager = new OrionEventManager();
        return manager;
    }

    public OrionExecutor executor() {
        if (executor == null) {
            executor = new OrionExecutor(2, new OrionThreadFactory());
            own(executor);
        }
        return executor;
    }

    public void awaitEvents() {
        if (manager != null && manager.isRunning()) manager.publishAndWait(new Barrier());
    }

    @Override
    public void afterEach(ExtensionContext context) {
        close();
    }

    @Override
    public void close() {
        IllegalStateException failures = null;
        try {
            awaitEvents();
            for (AutoCloseable resource : resources.reversed()) {
                try {
                    resource.close();
                } catch (Exception failure) {
                    if (failures == null) {
                        failures = new IllegalStateException("Cannot close test resource", failure);
                    } else {
                        failures.addSuppressed(failure);
                    }
                }
            }
        } finally {
            resources.clear();
            executor = null;
            if (manager != null) {
                manager.onStop();
                manager = null;
            }
        }
        if (failures != null) throw failures;
    }

    private static final class Barrier extends OrionEvent {}
}
