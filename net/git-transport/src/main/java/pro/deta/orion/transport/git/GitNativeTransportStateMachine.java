package pro.deta.orion.transport.git;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import pro.deta.orion.config.schema.GitTransportConfig;
import pro.deta.orion.lifecycle.OrionLifecycleStateMachine;
import pro.deta.orion.lifecycle.state.ActionBinding;
import pro.deta.orion.lifecycle.state.ActionId;
import pro.deta.orion.lifecycle.state.StateMachine;
import pro.deta.orion.lifecycle.state.StateMachineDefinition;
import pro.deta.orion.lifecycle.state.StateMachineDefinition.State;
import pro.deta.orion.lifecycle.state.StateTransitionEvent;
import pro.deta.orion.lifecycle.state.StateMachineEventSubscriber;
import pro.deta.orion.lifecycle.state.StateMachineSnapshot;
import pro.deta.orion.lifecycle.state.StateMachineSubscription;
import pro.deta.orion.lifecycle.state.Void;

import javax.inject.Provider;
import java.util.Objects;

import static pro.deta.orion.lifecycle.state.StateMachineDefinition.*;

@Singleton
public final class GitNativeTransportStateMachine implements OrionLifecycleStateMachine {
    public static final State RUNNING = state("RUNNING");

    private final GitTransportConfig config;
    private final Provider<GitNativeTransportService> serviceProvider;
    private GitNativeTransportService service;
    private final ActionBinding<Void> start = ActionId.START.bind(this::startGitTransport);
    private final ActionBinding<Void> stop = ActionId.STOP.bind(this::stopGitTransport);
    private final StateMachineDefinition definition;
    private final StateMachine stateMachine;

    @Inject
    public GitNativeTransportStateMachine(
            GitTransportConfig config,
            Provider<GitNativeTransportService> serviceProvider) {
        this.config = Objects.requireNonNull(config, "config");
        this.serviceProvider = Objects.requireNonNull(serviceProvider, "serviceProvider");
        definition = defineStateMachine();
        stateMachine = definition.newStateMachine();
    }

    private StateMachineDefinition defineStateMachine() {
        return StateMachineDefinition.define()
                .from(NEW)
                .on(start)
                .to(RUNNING)
                .failTo(ERR)

                .from(NEW)
                .on(stop)
                .to(FIN)
                .failTo(ERR)

                .from(RUNNING)
                .on(stop)
                .to(FIN)
                .failTo(ERR)

                .from(ERR)
                .on(stop)
                .to(FIN)
                .failTo(ERR)
                .build();
    }

    public StateMachineDefinition definition() {
        return definition;
    }

    public GitNativeTransportService service() {
        return resolveService();
    }

    public boolean isEnabled() {
        return config != null && config.isEnabled();
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

    public State currentState() {
        return stateMachine.currentState();
    }

    public StateMachineSnapshot snapshot() {
        return stateMachine.snapshot();
    }

    public String describe() {
        return stateMachine.describe();
    }

    public StateMachineSubscription subscribe(StateMachineEventSubscriber subscriber) {
        return stateMachine.subscribe(subscriber);
    }

    public StateTransitionEvent start() {
        return stateMachine.execute(start, Void.EMPTY);
    }

    public StateTransitionEvent stop() {
        return stateMachine.execute(stop, Void.EMPTY);
    }

    private void startGitTransport(Void ignored) {
        if (isEnabled()) {
            resolveService().onStart();
        }
    }

    private void stopGitTransport(Void ignored) {
        if (isEnabled()) {
            resolveService().onStop();
        }
    }

    private GitNativeTransportService resolveService() {
        if (service == null) {
            service = serviceProvider.get();
        }
        return service;
    }
}
