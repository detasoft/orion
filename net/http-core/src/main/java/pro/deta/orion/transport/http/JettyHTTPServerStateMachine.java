package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter;

import jakarta.inject.Provider;

/**
 * @AiRule This standalone transport adapter intentionally exposes its raw StateMachine as production API.
 */
@Singleton
public final class JettyHTTPServerStateMachine extends ServiceLifecycleStateMachineAdapter {

    @Inject
    public JettyHTTPServerStateMachine(
            Provider<JettyHTTPServer> serverProvider, Provider<AcmeCertificateService> certificatesProvider,
            Provider<GitPackCleanupTask> cleanupProvider) {
        super("http", new ServiceLifecycle() {
            private AcmeCertificateService certificates;
            private GitPackCleanupTask cleanup;

            @Override
            public void onStart() {
                JettyHTTPServer server = serverProvider.get();
                server.onStart();
                if (server.isRunning()) {
                    certificates = certificatesProvider.get();
                    certificates.startMaintenance(server::reloadHttpsCertificate);
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
                        if (certificates != null) certificates.stopMaintenance();
                    }
                } finally {
                    serverProvider.get().onStop();
                }
            }

            @Override
            public boolean isEnabled() { return serverProvider.get().isEnabled(); }

            @Override
            public boolean isRunning() { return serverProvider.get().isRunning(); }
        });
    }
}
