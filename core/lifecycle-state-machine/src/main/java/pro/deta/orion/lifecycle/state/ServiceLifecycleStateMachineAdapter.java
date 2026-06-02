package pro.deta.orion.lifecycle.state;

import java.util.Objects;

import static pro.deta.orion.lifecycle.state.StandardStateDefinition.DISABLED;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.ERR;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.FIN;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.NEW;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.RUNNING;

/**
 * Reusable state-machine adapter for leaf services with synchronous start/stop lifecycle hooks.
 */
public final class ServiceLifecycleStateMachineAdapter {
    private final ServiceLifecycle lifecycle;
    private final ActionBinding<Void> start = ActionId.START.bind(this::startService);
    private final ActionBinding<Void> stop = ActionId.STOP.bind(this::stopService);
    private final StateMachineDefinition definition;
    private final StateMachine stateMachine;

    public ServiceLifecycleStateMachineAdapter(String name, ServiceLifecycle lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        definition = StateMachineDefinition.define()
                .name(name)
                .from(NEW, DISABLED).on(start).to(DISABLED, RUNNING, ERR).post(this::resolveStartState)
                .from(NEW, DISABLED).on(stop).to(FIN, ERR)
                .from(RUNNING).on(stop).to(FIN, ERR)
                .from(ERR).on(stop).to(FIN, ERR)
                .build();
        stateMachine = definition.newStateMachine();
    }

    public StateMachineDefinition definition() {
        return definition;
    }

    public ActionBinding<Void> startAction() {
        return start;
    }

    public ActionBinding<Void> stopAction() {
        return stop;
    }

    public StateMachine stateMachine() {
        return stateMachine;
    }

    public StateMachineDefinition.State currentState() {
        return stateMachine.currentState();
    }

    public StateTransitionResult start() {
        return stateMachine.execute(start, Void.EMPTY);
    }

    public StateTransitionResult stop() {
        return stateMachine.execute(stop, Void.EMPTY);
    }

    private Void startService(Void ignored) throws Exception {
        lifecycle.onStart();
        return Void.EMPTY;
    }

    private StateMachineDefinition.State resolveStartState(StateTransitionResult result) {
        if (result.failed()) {
            return result.defaultState();
        }
        if (!lifecycle.isEnabled()) {
            return DISABLED;
        }
        return lifecycle.isRunning() ? RUNNING : ERR;
    }

    private Void stopService(Void ignored) throws Exception {
        StateMachineDefinition.State currentState = stateMachine.currentState();
        if (RUNNING.equals(currentState) || ERR.equals(currentState)) {
            lifecycle.onStop();
        }
        return Void.EMPTY;
    }

    public interface ServiceLifecycle {
        void onStart() throws Exception;

        void onStop() throws Exception;

        boolean isEnabled();

        boolean isRunning();
    }
}
