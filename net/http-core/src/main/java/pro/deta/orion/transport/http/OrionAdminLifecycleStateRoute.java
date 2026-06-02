package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.lifecycle.state.AggregateStateMachine;

import javax.inject.Named;

import static jakarta.servlet.http.HttpServletResponse.SC_OK;

public class OrionAdminLifecycleStateRoute extends BaseAdminRoute {
    private final AggregateStateMachine transportStateMachine;

    @Inject
    public OrionAdminLifecycleStateRoute(@Named("transport") AggregateStateMachine transportStateMachine) {
        super(OrionAdminPaths.LIFECYCLE_TRANSPORTS, "GET");
        this.transportStateMachine = transportStateMachine;
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest req) {
        return OrionHttpResponse.text(SC_OK, transportStateMachine.describeStatus());
    }
}
