package pro.deta.orion.transport.http;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.config.ConfigurationChanged;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.keymaterial.KeyMaterialChanged;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter;
import pro.deta.orion.util.Subscription;

/**
 * @AiRule This standalone transport adapter intentionally exposes its raw StateMachine as production API.
 */
@Singleton
public final class JettyHTTPServerStateMachine extends ServiceLifecycleStateMachineAdapter {

    @Inject
    public JettyHTTPServerStateMachine(
            Provider<JettyHTTPServer> serverProvider,
            Provider<GitPackCleanupTask> cleanupProvider, OrionEventManager events, OrionExecutor executor) {
        super("http", new ServiceLifecycle() {
            private static final long RETRY_DELAY_SECONDS = 60;
            private GitPackCleanupTask cleanup;
            private JettyHTTPServer server;
            private final ReentrantLock activationExecution = new ReentrantLock();
            private volatile boolean activationEnabled;
            private ScheduledFuture<?> activationRetry;
            private Subscription keyMaterialChanges;
            private Subscription configurationChanges;

            @Override
            public void onStart() {
                server = serverProvider.get();
                server.onStart();
                if (server.isRunning()) {
                    activationEnabled = true;
                    keyMaterialChanges = events.subscribe(KeyMaterialChanged.class,
                            event -> keyMaterialChanged(event.resources()));
                    configurationChanges = events.subscribe(ConfigurationChanged.class, event -> {
                        if (server.usesConfiguration(event.snapshot())) configurationChanged();
                    });
                    cleanup = cleanupProvider.get();
                    cleanup.start();
                }
            }

            @Override
            public void onStop() throws Exception {
                try {
                    try {
                        if (cleanup != null) cleanup.stop();
                    } finally {
                        stopActivation();
                    }
                } finally {
                    serverProvider.get().onStop();
                }
            }

            @Override
            public boolean isEnabled() { return serverProvider.get().isEnabled(); }

            @Override
            public boolean isRunning() { return serverProvider.get().isRunning(); }

            private synchronized void keyMaterialChanged(java.util.Set<String> resources) {
                if (!activationEnabled || server == null
                        || !server.usesKeyMaterialResources(resources)) return;
                scheduleActivation(0);
            }

            private synchronized void configurationChanged() {
                if (activationEnabled) scheduleActivation(0);
            }

            private synchronized void scheduleActivation(long delaySeconds) {
                if (!activationEnabled) return;
                if (activationRetry != null) activationRetry.cancel(false);
                activationRetry = executor.schedule(this::activateCertificate, delaySeconds, TimeUnit.SECONDS);
            }

            private void activateCertificate() {
                try {
                    activationExecution.lockInterruptibly();
                    try {
                        if (!activationEnabled) return;
                        server.reloadHttpsCertificate();
                    } catch (RuntimeException failure) {
                        System.getLogger(JettyHTTPServerStateMachine.class.getName())
                                .log(System.Logger.Level.WARNING,
                                        "HTTPS certificate activation failed; retrying automatically", failure);
                        scheduleActivation(RETRY_DELAY_SECONDS);
                    } finally {
                        activationExecution.unlock();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }

            private void stopActivation() throws InterruptedException {
                synchronized (this) {
                    activationEnabled = false;
                    if (keyMaterialChanges != null) {
                        keyMaterialChanges.close();
                        keyMaterialChanges = null;
                    }
                    if (configurationChanges != null) {
                        configurationChanges.close();
                        configurationChanges = null;
                    }
                    if (activationRetry != null) activationRetry.cancel(true);
                    activationRetry = null;
                }
                if (!activationExecution.tryLock(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("HTTPS certificate activation has not stopped");
                }
                activationExecution.unlock();
            }
        });
    }
}
