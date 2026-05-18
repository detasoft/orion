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
import java.util.concurrent.ExecutorService;

public final class StateMachineDefinition {
    public static final State NEW = new State("NEW");
    public static final State FIN = new State("FIN");
    public static final State ERR = state("ERR");

    private final Map<StateActionKey, StateTransition<?>> transitions;
    private final Set<State> terminalStates;
    private final Set<State> states;
    private final Map<String, StateMachine> children;
    private final ComputedStateResolver computedStateResolver;
    private final ExecutorService childExecutor;

    private StateMachineDefinition(
            Map<StateActionKey, StateTransition<?>> transitions,
            Set<State> terminalStates,
            Map<String, StateMachine> children,
            ComputedStateResolver computedStateResolver,
            ExecutorService childExecutor) {
        this.transitions = Collections.unmodifiableMap(new LinkedHashMap<>(transitions));
        LinkedHashSet<State> allTerminalStates = new LinkedHashSet<>();
        allTerminalStates.add(FIN);
        allTerminalStates.addAll(terminalStates);
        this.terminalStates = Collections.unmodifiableSet(allTerminalStates);
        this.states = collectStates(this.transitions, allTerminalStates);
        this.children = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(children, "children")));
        this.computedStateResolver = Objects.requireNonNull(computedStateResolver, "computedStateResolver");
        this.childExecutor = childExecutor;
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

    public Set<State> states() {
        return states;
    }

    public Map<String, StateMachine> children() {
        return children;
    }

    public ComputedStateResolver computedStateResolver() {
        return computedStateResolver;
    }

    ExecutorService childExecutor() {
        return childExecutor;
    }

    public <A> Optional<StateTransition<A>> transition(State state, ActionBinding<A> action) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(action, "action");
        @SuppressWarnings("unchecked")
        StateTransition<A> transition = (StateTransition<A>) transitions.get(new StateActionKey(state, action.id()));
        if (transition != null && transition.action() != action) {
            return Optional.empty();
        }
        return Optional.ofNullable(transition);
    }

    public Optional<StateTransition<?>> transition(State state, ActionId actionId) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(actionId, "actionId");
        return Optional.ofNullable(transitions.get(new StateActionKey(state, actionId)));
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

    List<StateTransition<?>> transitions() {
        return List.copyOf(transitions.values());
    }

    public Set<ActionId> availableActions(State state) {
        Objects.requireNonNull(state, "state");
        Set<ActionId> result = new LinkedHashSet<>();
        for (StateTransition<?> transition : transitionsFrom(state)) {
            result.add(transition.actionId());
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

    private static Set<State> collectStates(
            Map<StateActionKey, StateTransition<?>> transitions,
            Set<State> terminalStates) {
        LinkedHashSet<State> result = new LinkedHashSet<>();
        result.add(NEW);
        for (StateTransition<?> transition : transitions.values()) {
            result.add(transition.from());
            result.add(transition.to());
            result.add(transition.failureState());
        }
        result.addAll(terminalStates);
        return Collections.unmodifiableSet(result);
    }

    public static final class Builder {
        private final Map<StateActionKey, StateTransition<?>> transitions = new LinkedHashMap<>();
        private final Set<State> terminalStates = new LinkedHashSet<>();
        private final Map<String, StateMachine> children = new LinkedHashMap<>();
        private ComputedStateResolver computedStateResolver = (physicalState, childStates) -> physicalState;
        private ExecutorService childExecutor;

        private Builder() {
        }

        public Builder terminal(State terminalState) {
            terminalStates.add(Objects.requireNonNull(terminalState, "terminalState"));
            return this;
        }

        public Builder child(String name, StateMachine child) {
            requireName(name, "name");
            Objects.requireNonNull(child, "child");
            if (children.containsKey(name)) {
                throw new IllegalArgumentException("Duplicate child state machine " + name);
            }
            children.put(name, child);
            return this;
        }

        public Builder computedState(ComputedStateResolver computedStateResolver) {
            this.computedStateResolver = Objects.requireNonNull(computedStateResolver, "computedStateResolver");
            return this;
        }

        public Builder childExecutor(ExecutorService childExecutor) {
            this.childExecutor = Objects.requireNonNull(childExecutor, "childExecutor");
            return this;
        }

        public FromRule from(State state) {
            return new FromRule(this, state);
        }

        private <A> Builder addTransition(
                State from,
                ActionId actionId,
                ActionBinding<A> action,
                State to,
                State failureState) {
            StateTransition<A> transition = new StateTransition<>(
                    Objects.requireNonNull(from, "from"),
                    Objects.requireNonNull(actionId, "actionId"),
                    action,
                    Objects.requireNonNull(to, "to"),
                    Objects.requireNonNull(failureState, "failureState"));
            StateActionKey key = new StateActionKey(from, actionId);
            if (transitions.containsKey(key)) {
                throw new IllegalArgumentException("Duplicate transition action id " + actionId + " for state " + from);
            }
            transitions.put(key, transition);
            return this;
        }

        public StateMachineDefinition build() {
            return new StateMachineDefinition(
                    new LinkedHashMap<>(transitions),
                    new LinkedHashSet<>(terminalStates),
                    new LinkedHashMap<>(children),
                    computedStateResolver,
                    childExecutor);
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
            return new ActionRule<>(builder, state, action.id(), action);
        }
    }

    public static final class ActionRule<A> {
        private final Builder builder;
        private final State from;
        private final ActionId actionId;
        private final ActionBinding<A> action;

        private ActionRule(
                Builder builder,
                State from,
                ActionId actionId,
                ActionBinding<A> action) {
            this.builder = Objects.requireNonNull(builder, "builder");
            this.from = Objects.requireNonNull(from, "from");
            this.actionId = Objects.requireNonNull(actionId, "actionId");
            this.action = Objects.requireNonNull(action, "action");
        }

        public TargetRule<A> to(State to) {
            return new TargetRule<>(builder, from, actionId, action, to);
        }

        public TargetRule<A> stay() {
            return to(from);
        }
    }

    public static final class TargetRule<A> {
        private final Builder builder;
        private final State from;
        private final ActionId actionId;
        private final ActionBinding<A> action;
        private final State to;

        private TargetRule(
                Builder builder,
                State from,
                ActionId actionId,
                ActionBinding<A> action,
                State to) {
            this.builder = Objects.requireNonNull(builder, "builder");
            this.from = Objects.requireNonNull(from, "from");
            this.actionId = Objects.requireNonNull(actionId, "actionId");
            this.action = Objects.requireNonNull(action, "action");
            this.to = Objects.requireNonNull(to, "to");
        }

        public Builder failTo(State failureState) {
            return builder.addTransition(from, actionId, action, to, failureState);
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

    private record StateActionKey(State state, ActionId actionId) {
        private StateActionKey {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(actionId, "actionId");
        }
    }

    private static String requireName(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
