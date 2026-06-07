package pro.deta.orion.component;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import pro.deta.orion.acl.OrionAccessControlStateMachine;
import pro.deta.orion.event.OrionEventManagerStateMachine;
import pro.deta.orion.git.OrionJGitRuntimeStateMachine;
import pro.deta.orion.internal.OrionExecutorStateMachine;
import pro.deta.orion.lifecycle.state.ActionBinding;
import pro.deta.orion.lifecycle.state.ActionId;
import pro.deta.orion.lifecycle.state.AggregateLifecycleStateMachineAdapter;
import pro.deta.orion.lifecycle.state.AggregateStateMachine;
import pro.deta.orion.lifecycle.state.StateMachineDefinition;
import pro.deta.orion.lifecycle.state.StateTransitionResult;
import pro.deta.orion.transport.TransportLifecycleStateMachine;

import java.util.List;
import java.util.function.Supplier;

import static pro.deta.orion.lifecycle.state.StandardStateDefinition.DISABLED;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.ERR;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.FIN;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.NEW;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.RUNNING;

/**
 * Root lifecycle state machine for the Orion process.
 *
 * <p>@AiRule Keep startup order explicit: executor, JGit runtime, event manager, ACL, then transports. The executor is
 * needed for lifecycle work, JGit global state must exist before git-backed storage is used, the event manager must be
 * running before ACL registers and publishes reload events, ACL must be loaded before any transport exposes
 * authenticated endpoints, and transports are the final externally visible services. Shutdown must use the reverse
 * order so transports close before auth/runtime dependencies and the executor stops last.</p>
 */
@Singleton
public final class OrionRuntimeStateMachine extends AggregateLifecycleStateMachineAdapter {

    @Inject
    public OrionRuntimeStateMachine(
            OrionExecutorStateMachine executor,
            OrionJGitRuntimeStateMachine jgitRuntime,
            OrionEventManagerStateMachine eventManager,
            OrionAccessControlStateMachine accessControl,
            TransportLifecycleStateMachine transports) {
        super(rootStateMachine(executor, jgitRuntime, eventManager, accessControl, transports));
    }

    private static AggregateStateMachine rootStateMachine(
            OrionExecutorStateMachine executor,
            OrionJGitRuntimeStateMachine jgitRuntime,
            OrionEventManagerStateMachine eventManager,
            OrionAccessControlStateMachine accessControl,
            TransportLifecycleStateMachine transports) {
        List<RuntimeChild> startOrder = List.of(
                child("executor", executor::start, executor::stop, executor::currentState),
                child("jgit-runtime", jgitRuntime::start, jgitRuntime::stop, jgitRuntime::currentState),
                child("event-manager", eventManager::start, eventManager::stop, eventManager::currentState),
                child("access-control", accessControl::start, accessControl::stop, accessControl::currentState),
                child("transports", transports::start, transports::stop, transports::currentState));
        ActionBinding<pro.deta.orion.lifecycle.state.Void> start =
                ActionId.START.bind(ignored -> startChildren(startOrder));
        ActionBinding<pro.deta.orion.lifecycle.state.Void> stop =
                ActionId.STOP.bind(ignored -> stopChildren(startOrder));
        StateMachineDefinition definition = StateMachineDefinition.define()
                .name("orion")
                .childPropagationMode(StateMachineDefinition.ChildPropagationMode.NONE)
                .child("executor", executor.stateMachine())
                .child("jgit-runtime", jgitRuntime.stateMachine())
                .child("event-manager", eventManager.stateMachine())
                .child("access-control", accessControl.stateMachine())
                .child("transports", transports.stateMachine())
                .from(NEW, DISABLED).on(start).to(DISABLED, RUNNING, ERR).post(result ->
                        resolveStartState(result, startOrder))
                .from(NEW, DISABLED).on(stop).to(FIN, ERR)
                .from(RUNNING).on(stop).to(FIN, ERR)
                .from(ERR).on(stop).to(FIN, ERR)
                .build();
        return new AggregateStateMachine(definition);
    }

    private static RuntimeChild child(
            String name,
            RuntimeAction start,
            RuntimeAction stop,
            Supplier<StateMachineDefinition.State> state) {
        return new RuntimeChild(name, start, stop, state);
    }

    private static pro.deta.orion.lifecycle.state.Void startChildren(List<RuntimeChild> children) throws Exception {
        for (RuntimeChild child : children) {
            child.start().run();
        }
        return pro.deta.orion.lifecycle.state.Void.EMPTY;
    }

    private static pro.deta.orion.lifecycle.state.Void stopChildren(List<RuntimeChild> children) throws Exception {
        for (int i = children.size() - 1; i >= 0; i--) {
            children.get(i).stop().run();
        }
        return pro.deta.orion.lifecycle.state.Void.EMPTY;
    }

    private static StateMachineDefinition.State resolveStartState(
            StateTransitionResult result,
            List<RuntimeChild> children) {
        if (result.failed()) {
            return result.defaultState();
        }
        for (RuntimeChild child : children) {
            StateMachineDefinition.State state = child.state().get();
            if (ERR.equals(state)) {
                return ERR;
            }
            if (RUNNING.equals(state)) {
                return RUNNING;
            }
        }
        return DISABLED;
    }

    @FunctionalInterface
    private interface RuntimeAction {
        void run() throws Exception;
    }

    private record RuntimeChild(
            String name,
            RuntimeAction start,
            RuntimeAction stop,
            Supplier<StateMachineDefinition.State> state) {
    }
}
