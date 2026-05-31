package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.lifecycle.state.StateMachine;

import javax.inject.Named;

import static jakarta.servlet.http.HttpServletResponse.SC_OK;

public class OrionAdminLifecycleStateRoute extends BaseAdminRoute {
    private final StateMachine transportStateMachine;

    @Inject
    public OrionAdminLifecycleStateRoute(@Named("transport") StateMachine transportStateMachine) {
        super(OrionAdminPaths.LIFECYCLE_TRANSPORTS, "GET");
        this.transportStateMachine = transportStateMachine;
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest req) {
        return OrionHttpResponse.text(SC_OK, transportStateMachine.describeStatus());
    }
}
