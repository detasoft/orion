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

    public pro.deta.orion.storage.InMemoryBlobStorage pkcs12Store(char[] password) {
        byte[] bytes = pkcs12Content(password);
        try {
            return new pro.deta.orion.storage.InMemoryBlobStorage(bytes, false, events());
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    public static byte[] pkcs12Content(char[] password) {
        try (java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            java.security.KeyStore store = java.security.KeyStore.getInstance("PKCS12");
            store.load(null, password);
            store.store(output, password);
            return output.toByteArray();
        } catch (java.io.IOException | java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("Cannot initialize test key store", failure);
        }
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
        RuntimeException failures = null;
        try {
            try {
                awaitEvents();
            } catch (RuntimeException failure) {
                failures = failure;
            }
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
