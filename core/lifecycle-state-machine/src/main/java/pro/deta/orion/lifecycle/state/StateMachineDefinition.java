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
            return from(state, new State[0]);
        }

        public FromRule from(State firstState, State... additionalStates) {
            Objects.requireNonNull(additionalStates, "additionalStates");
            LinkedHashSet<State> states = new LinkedHashSet<>();
            addFromState(states, firstState);
            for (State state : additionalStates) {
                addFromState(states, state);
            }
            return new FromRule(this, List.copyOf(states));
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

        private <A> Builder addTransitions(
                Map<State, State> targets,
                ActionId actionId,
                ActionBinding<A> action,
                State failureState) {
            Objects.requireNonNull(targets, "targets");
            Objects.requireNonNull(actionId, "actionId");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(failureState, "failureState");
            Map<StateActionKey, State> transitionsToAdd = new LinkedHashMap<>();
            for (Map.Entry<State, State> entry : targets.entrySet()) {
                State from = Objects.requireNonNull(entry.getKey(), "from");
                State to = Objects.requireNonNull(entry.getValue(), "to");
                StateActionKey key = new StateActionKey(from, actionId);
                if (transitions.containsKey(key)) {
                    throw new IllegalArgumentException(
                            "Duplicate transition action id " + actionId + " for state " + from);
                }
                transitionsToAdd.put(key, to);
            }
            for (Map.Entry<StateActionKey, State> entry : transitionsToAdd.entrySet()) {
                StateActionKey key = entry.getKey();
                transitions.put(key, new StateTransition<>(key.state(), actionId, action, entry.getValue(), failureState));
            }
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
        private final List<State> states;

        private FromRule(Builder builder, List<State> states) {
            this.builder = Objects.requireNonNull(builder, "builder");
            this.states = List.copyOf(Objects.requireNonNull(states, "states"));
        }

        public <A> ActionRule<A> on(ActionBinding<A> action) {
            return new ActionRule<>(builder, states, action.id(), action);
        }
    }

    public static final class ActionRule<A> {
        private final Builder builder;
        private final List<State> fromStates;
        private final ActionId actionId;
        private final ActionBinding<A> action;

        private ActionRule(
                Builder builder,
                List<State> fromStates,
                ActionId actionId,
                ActionBinding<A> action) {
            this.builder = Objects.requireNonNull(builder, "builder");
            this.fromStates = List.copyOf(Objects.requireNonNull(fromStates, "fromStates"));
            this.actionId = Objects.requireNonNull(actionId, "actionId");
            this.action = Objects.requireNonNull(action, "action");
        }

        public TargetRule<A> to(State to) {
            Objects.requireNonNull(to, "to");
            Map<State, State> targets = new LinkedHashMap<>();
            for (State from : fromStates) {
                targets.put(from, to);
            }
            return new TargetRule<>(builder, targets, actionId, action);
        }

        public TargetRule<A> stay() {
            Map<State, State> targets = new LinkedHashMap<>();
            for (State from : fromStates) {
                targets.put(from, from);
            }
            return new TargetRule<>(builder, targets, actionId, action);
        }
    }

    public static final class TargetRule<A> {
        private final Builder builder;
        private final Map<State, State> targets;
        private final ActionId actionId;
        private final ActionBinding<A> action;

        private TargetRule(
                Builder builder,
                Map<State, State> targets,
                ActionId actionId,
                ActionBinding<A> action) {
            this.builder = Objects.requireNonNull(builder, "builder");
            this.targets = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(targets, "targets")));
            this.actionId = Objects.requireNonNull(actionId, "actionId");
            this.action = Objects.requireNonNull(action, "action");
        }

        public Builder failTo(State failureState) {
            if (targets.size() == 1) {
                Map.Entry<State, State> target = targets.entrySet().iterator().next();
                return builder.addTransition(target.getKey(), actionId, action, target.getValue(), failureState);
            }
            return builder.addTransitions(targets, actionId, action, failureState);
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

    private static void addFromState(Set<State> states, State state) {
        Objects.requireNonNull(state, "state");
        if (!states.add(state)) {
            throw new IllegalArgumentException("Duplicate from state " + state);
        }
    }
}
