package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter;

/** Runs certificate renewal independently of the HTTP transport lifecycle. */
@Singleton
public final class AcmeCertificateServiceStateMachine extends ServiceLifecycleStateMachineAdapter {
    @Inject
    public AcmeCertificateServiceStateMachine(Provider<AcmeCertificateService> certificates) {
        super("acme", certificates);
    }
}
