package pro.deta.orion.lifecycle.state;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class StateMachineDefinition {
    public static final State NEW = new State("NEW");
    public static final State FIN = new State("FIN");
    public static final State ERR = state("ERR");

    private final Map<StateActionKey, StateTransition<?>> transitions;
    private final Set<State> terminalStates;

    private StateMachineDefinition(
            Map<StateActionKey, StateTransition<?>> transitions,
            Set<State> terminalStates) {
        this.transitions = Collections.unmodifiableMap(new LinkedHashMap<>(transitions));
        LinkedHashSet<State> allTerminalStates = new LinkedHashSet<>();
        allTerminalStates.add(FIN);
        allTerminalStates.addAll(terminalStates);
        this.terminalStates = Collections.unmodifiableSet(allTerminalStates);
    }

    public static State state(String name) {
        State state = new State(name);
        if (NEW.equals(state)) {
            return NEW;
        }
        if (FIN.equals(state)) {
            return FIN;
        }
        return state;
    }

    public static Builder define() {
        return new Builder();
    }

    public State initialState() {
        return NEW;
    }

    public boolean isTerminalState(State state) {
        return terminalStates.contains(state);
    }

    public <A> Optional<StateTransition<A>> transition(State state, ActionBinding<A> action) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(action, "action");
        @SuppressWarnings("unchecked")
        StateTransition<A> transition = (StateTransition<A>) transitions.get(new StateActionKey(state, action));
        return Optional.ofNullable(transition);
    }

    public List<StateTransition<?>> transitionsFrom(State state) {
        Objects.requireNonNull(state, "state");
        List<StateTransition<?>> result = new ArrayList<>();
        for (Map.Entry<StateActionKey, StateTransition<?>> entry : transitions.entrySet()) {
            if (Objects.equals(state, entry.getKey().state())) {
                result.add(entry.getValue());
            }
        }
        return List.copyOf(result);
    }

    public Set<ActionBinding<?>> availableActions(State state) {
        Objects.requireNonNull(state, "state");
        Set<ActionBinding<?>> result = new LinkedHashSet<>();
        for (StateTransition<?> transition : transitionsFrom(state)) {
            result.add(transition.action());
        }
        return Collections.unmodifiableSet(result);
    }

    public String transitionDiagram() {
        StringBuilder builder = new StringBuilder();
        builder.append("transitions:");
        if (transitions.isEmpty()) {
            builder.append("\n  <none>");
            return builder.toString();
        }
        for (StateTransition<?> transition : transitions.values()) {
            builder.append("\n  ").append(transition.describe());
        }
        return builder.toString();
    }

    public StateMachine newStateMachine() {
        return StateMachine.create(this);
    }

    public static final class Builder {
        private final Map<StateActionKey, StateTransition<?>> transitions = new LinkedHashMap<>();
        private final Set<State> terminalStates = new LinkedHashSet<>();

        private Builder() {
        }

        public Builder terminal(State terminalState) {
            terminalStates.add(Objects.requireNonNull(terminalState, "terminalState"));
            return this;
        }

        public FromRule from(State state) {
            return new FromRule(this, state);
        }

        private <A> Builder addTransition(
                State from,
                ActionBinding<A> action,
                State to,
                State failureState) {
            StateTransition<A> transition = new StateTransition<>(
                    Objects.requireNonNull(from, "from"),
                    Objects.requireNonNull(action, "action"),
                    Objects.requireNonNull(to, "to"),
                    Objects.requireNonNull(failureState, "failureState"));
            StateActionKey key = new StateActionKey(from, action);
            if (transitions.containsKey(key)) {
                throw new IllegalArgumentException("Duplicate transition for state " + from + " and action " + action);
            }
            transitions.put(key, transition);
            return this;
        }

        public StateMachineDefinition build() {
            return new StateMachineDefinition(
                    new LinkedHashMap<>(transitions),
                    new LinkedHashSet<>(terminalStates));
        }
    }

    public static final class FromRule {
        private final Builder builder;
        private final State state;

        private FromRule(Builder builder, State state) {
            this.builder = Objects.requireNonNull(builder, "builder");
            this.state = Objects.requireNonNull(state, "state");
        }

        public <A> ActionRule<A> on(ActionBinding<A> action) {
            return new ActionRule<>(builder, state, action);
        }
    }

    public static final class ActionRule<A> {
        private final Builder builder;
        private final State from;
        private final ActionBinding<A> action;

        private ActionRule(Builder builder, State from, ActionBinding<A> action) {
            this.builder = Objects.requireNonNull(builder, "builder");
            this.from = Objects.requireNonNull(from, "from");
            this.action = Objects.requireNonNull(action, "action");
        }

        public TargetRule<A> to(State to) {
            return new TargetRule<>(builder, from, action, to);
        }

        public TargetRule<A> stay() {
            return to(from);
        }
    }

    public static final class TargetRule<A> {
        private final Builder builder;
        private final State from;
        private final ActionBinding<A> action;
        private final State to;

        private TargetRule(Builder builder, State from, ActionBinding<A> action, State to) {
            this.builder = Objects.requireNonNull(builder, "builder");
            this.from = Objects.requireNonNull(from, "from");
            this.action = Objects.requireNonNull(action, "action");
            this.to = Objects.requireNonNull(to, "to");
        }

        public Builder failTo(State failureState) {
            return builder.addTransition(from, action, to, failureState);
        }
    }

    public static final class State {
        private final String name;

        private State(String name) {
            this.name = Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("State name must not be blank");
            }
        }

        public String name() {
            return name;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof State state)) {
                return false;
            }
            return name.equals(state.name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private record StateActionKey(State state, ActionBinding<?> action) {
        private StateActionKey {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(action, "action");
        }
    }
}
