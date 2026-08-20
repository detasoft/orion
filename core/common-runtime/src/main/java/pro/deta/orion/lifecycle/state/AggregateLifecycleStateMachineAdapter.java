package pro.deta.orion.lifecycle.state;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static pro.deta.orion.lifecycle.state.StandardStateDefinition.DISABLED;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.ERR;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.FIN;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.NEW;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.RUNNING;

/**
 * Reusable state-machine adapter for aggregate services that propagate start/stop actions to child machines.
 */
public class AggregateLifecycleStateMachineAdapter {
    private final AggregateStateMachine aggregateStateMachine;

    protected AggregateLifecycleStateMachineAdapter(AggregateStateMachine aggregateStateMachine) {
        this.aggregateStateMachine = Objects.requireNonNull(aggregateStateMachine, "aggregateStateMachine");
    }

    public static Builder define(String name) {
        return new Builder(name);
    }

    public StateMachineDefinition.State currentState() {
        return aggregateStateMachine.currentState();
    }

    public Map<String, StateMachineStatus> childStatuses() {
        return aggregateStateMachine.childStatuses();
    }

    public StateMachineStatus status() {
        return aggregateStateMachine.status();
    }

    public AggregateStateMachine aggregateStateMachine() {
        return aggregateStateMachine;
    }

    public StateMachine stateMachine() {
        return aggregateStateMachine.stateMachine();
    }

    public List<StateTransitionResult> start() {
        return aggregateStateMachine.start();
    }

    public List<StateTransitionResult> stop() {
        return aggregateStateMachine.stop();
    }

    public static final class Builder {
        private final String name;
        private final Map<String, StateMachine> children = new LinkedHashMap<>();
        private StateMachineDefinition.ChildPropagationMode childPropagationMode =
                StateMachineDefinition.ChildPropagationMode.SEQUENTIAL;

        private Builder(String name) {
            this.name = requireName(name);
        }

        public Builder child(String name, StateMachine child) {
            requireName(name);
            Objects.requireNonNull(child, "child");
            if (children.containsKey(name)) {
                throw new IllegalArgumentException("Duplicate child state machine " + name);
            }
            children.put(name, child);
            return this;
        }

        public Builder child(String name, AggregateStateMachine child) {
            Objects.requireNonNull(child, "child");
            return child(name, child.stateMachine());
        }

        public Builder childPropagationMode(StateMachineDefinition.ChildPropagationMode mode) {
            childPropagationMode = Objects.requireNonNull(mode, "mode");
            return this;
        }

        public AggregateLifecycleStateMachineAdapter build() {
            return new AggregateLifecycleStateMachineAdapter(buildAggregateStateMachine());
        }

        public AggregateStateMachine buildAggregateStateMachine() {
            StateMachineDefinition.Builder builder = StateMachineDefinition.define()
                    .name(name)
                    .childPropagationMode(childPropagationMode);
            for (Map.Entry<String, StateMachine> child : children.entrySet()) {
                builder.child(child.getKey(), child.getValue());
            }
            StateMachineDefinition definition = builder
                    .from(NEW, DISABLED).on(ActionId.START).to(DISABLED, RUNNING, ERR)
                    .post(this::resolveStartState)
                    .from(NEW, DISABLED).on(ActionId.STOP).to(FIN, ERR)
                    .from(RUNNING).on(ActionId.STOP).to(FIN, ERR)
                    .from(ERR).on(ActionId.STOP).to(FIN, ERR)
                    .build();
            return new AggregateStateMachine(definition);
        }

        private StateMachineDefinition.State resolveStartState(StateTransitionResult result) {
            if (result.failed()) {
                return result.defaultState();
            }
            for (StateMachine child : children.values()) {
                StateMachineDefinition.State state = child.currentState();
                if (ERR.equals(state)) {
                    return ERR;
                }
                if (RUNNING.equals(state)) {
                    return RUNNING;
                }
            }
            return DISABLED;
        }

        private static String requireName(String value) {
            Objects.requireNonNull(value, "name");
            if (value.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            return value;
        }
    }
}
