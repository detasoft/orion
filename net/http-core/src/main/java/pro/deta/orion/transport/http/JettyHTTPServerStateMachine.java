package pro.deta.orion.transport.http;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import pro.deta.orion.event.OrionEventManager;
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
            Provider<JettyHTTPServer> serverProvider, Provider<AcmeCertificateService> certificatesProvider,
            Provider<GitPackCleanupTask> cleanupProvider, OrionEventManager events) {
        super("http", new ServiceLifecycle() {
            private static final long RETRY_DELAY_SECONDS = 60;
            private AcmeCertificateService certificates;
            private GitPackCleanupTask cleanup;
            private JettyHTTPServer server;
            private ScheduledExecutorService activation;
            private ScheduledFuture<?> activationRetry;
            private Subscription keyMaterialChanges;

            @Override
            public void onStart() {
                server = serverProvider.get();
                server.onStart();
                if (server.isRunning()) {
                    activation = Executors.newSingleThreadScheduledExecutor(
                            Thread.ofPlatform().daemon(true).name("https-certificate-activation").factory());
                    keyMaterialChanges = events.subscribe(KeyMaterialChanged.class,
                            event -> keyMaterialChanged(event.resources()));
                    certificates = certificatesProvider.get();
                    certificates.startMaintenance();
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
                        try {
                            stopActivation();
                        } finally {
                            if (certificates != null) certificates.stopMaintenance();
                        }
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
                if (activation == null || activation.isShutdown() || server == null
                        || !server.usesKeyMaterialResources(resources)) return;
                scheduleActivation(0);
            }

            private synchronized void scheduleActivation(long delaySeconds) {
                if (activation == null || activation.isShutdown()) return;
                if (activationRetry != null) activationRetry.cancel(false);
                activationRetry = activation.schedule(this::activateCertificate, delaySeconds, TimeUnit.SECONDS);
            }

            private void activateCertificate() {
                JettyHTTPServer activeServer = server;
                if (activeServer == null) return;
                try {
                    activeServer.reloadHttpsCertificate();
                } catch (RuntimeException failure) {
                    System.getLogger(JettyHTTPServerStateMachine.class.getName()).log(System.Logger.Level.WARNING,
                            "HTTPS certificate activation failed; retrying automatically", failure);
                    scheduleActivation(RETRY_DELAY_SECONDS);
                }
            }

            private synchronized void stopActivation() throws InterruptedException {
                if (keyMaterialChanges != null) {
                    keyMaterialChanges.close();
                    keyMaterialChanges = null;
                }
                if (activation == null) return;
                activation.shutdownNow();
                if (!activation.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("HTTPS certificate activation has not stopped");
                }
                activation = null;
                activationRetry = null;
            }
        });
    }
}
