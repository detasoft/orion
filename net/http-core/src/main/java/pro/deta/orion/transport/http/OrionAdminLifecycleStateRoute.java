package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.lifecycle.state.AggregateStateMachine;

import jakarta.inject.Named;

import static jakarta.servlet.http.HttpServletResponse.SC_OK;

public class OrionAdminLifecycleStateRoute extends BaseAdminRoute {
    private final AggregateStateMachine runtimeStateMachine;

    @Inject
    public OrionAdminLifecycleStateRoute(@Named("runtime") AggregateStateMachine runtimeStateMachine) {
        super(OrionAdminPaths.LIFECYCLE_STATE, "GET");
        this.runtimeStateMachine = runtimeStateMachine;
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest req) {
        return OrionHttpResponse.text(SC_OK, runtimeStateMachine.describeStatus());
    }
}
