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

public final class StateMachineDefinition<S> {
    private final S initialState;
    private final Map<StateActionKey<S>, StateTransition<S, ?>> transitions;
    private final Set<S> terminalStates;

    private StateMachineDefinition(
            S initialState,
            Map<StateActionKey<S>, StateTransition<S, ?>> transitions,
            Set<S> terminalStates) {
        this.initialState = Objects.requireNonNull(initialState, "initialState");
        this.transitions = Collections.unmodifiableMap(new LinkedHashMap<>(transitions));
        this.terminalStates = Collections.unmodifiableSet(new LinkedHashSet<>(terminalStates));
    }

    public static <S> Builder<S> startingAt(S initialState) {
        return new Builder<>(initialState);
    }

    public S initialState() {
        return initialState;
    }

    public boolean isTerminalState(S state) {
        return terminalStates.contains(state);
    }

    public <A> Optional<StateTransition<S, A>> transition(S state, ActionBinding<A> action) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(action, "action");
        @SuppressWarnings("unchecked")
        StateTransition<S, A> transition = (StateTransition<S, A>) transitions.get(new StateActionKey<>(state, action));
        return Optional.ofNullable(transition);
    }

    public List<StateTransition<S, ?>> transitionsFrom(S state) {
        Objects.requireNonNull(state, "state");
        List<StateTransition<S, ?>> result = new ArrayList<>();
        for (Map.Entry<StateActionKey<S>, StateTransition<S, ?>> entry : transitions.entrySet()) {
            if (Objects.equals(state, entry.getKey().state())) {
                result.add(entry.getValue());
            }
        }
        return List.copyOf(result);
    }

    public Set<ActionBinding<?>> availableActions(S state) {
        Objects.requireNonNull(state, "state");
        Set<ActionBinding<?>> result = new LinkedHashSet<>();
        for (StateTransition<S, ?> transition : transitionsFrom(state)) {
            result.add(transition.action());
        }
        return Collections.unmodifiableSet(result);
    }

    public StateMachine<S> newStateMachine() {
        return StateMachine.create(this);
    }

    public static final class Builder<S> {
        private final S initialState;
        private final Map<StateActionKey<S>, StateTransition<S, ?>> transitions = new LinkedHashMap<>();
        private final Set<S> terminalStates = new LinkedHashSet<>();

        private Builder(S initialState) {
            this.initialState = Objects.requireNonNull(initialState, "initialState");
        }

        public Builder<S> terminal(S terminalState) {
            terminalStates.add(Objects.requireNonNull(terminalState, "terminalState"));
            return this;
        }

        public FromRule<S> from(S state) {
            return new FromRule<>(this, state);
        }

        private <A> Builder<S> addTransition(
                S from,
                ActionBinding<A> action,
                S to,
                S failureState) {
            StateTransition<S, A> transition = new StateTransition<>(
                    Objects.requireNonNull(from, "from"),
                    Objects.requireNonNull(action, "action"),
                    Objects.requireNonNull(to, "to"),
                    Objects.requireNonNull(failureState, "failureState"));
            StateActionKey<S> key = new StateActionKey<>(from, action);
            if (transitions.containsKey(key)) {
                throw new IllegalArgumentException("Duplicate transition for state " + from + " and action " + action);
            }
            transitions.put(key, transition);
            return this;
        }

        public StateMachineDefinition<S> build() {
            return new StateMachineDefinition<>(
                    initialState,
                    new LinkedHashMap<>(transitions),
                    new LinkedHashSet<>(terminalStates));
        }
    }

    public static final class FromRule<S> {
        private final Builder<S> builder;
        private final S state;

        private FromRule(Builder<S> builder, S state) {
            this.builder = Objects.requireNonNull(builder, "builder");
            this.state = Objects.requireNonNull(state, "state");
        }

        public <A> ActionRule<S, A> on(ActionBinding<A> action) {
            return new ActionRule<>(builder, state, action);
        }
    }

    public static final class ActionRule<S, A> {
        private final Builder<S> builder;
        private final S from;
        private final ActionBinding<A> action;

        private ActionRule(Builder<S> builder, S from, ActionBinding<A> action) {
            this.builder = Objects.requireNonNull(builder, "builder");
            this.from = Objects.requireNonNull(from, "from");
            this.action = Objects.requireNonNull(action, "action");
        }

        public TargetRule<S, A> to(S to) {
            return new TargetRule<>(builder, from, action, to);
        }

        public TargetRule<S, A> stay() {
            return to(from);
        }
    }

    public static final class TargetRule<S, A> {
        private final Builder<S> builder;
        private final S from;
        private final ActionBinding<A> action;
        private final S to;

        private TargetRule(Builder<S> builder, S from, ActionBinding<A> action, S to) {
            this.builder = Objects.requireNonNull(builder, "builder");
            this.from = Objects.requireNonNull(from, "from");
            this.action = Objects.requireNonNull(action, "action");
            this.to = Objects.requireNonNull(to, "to");
        }

        public Builder<S> failTo(S failureState) {
            return builder.addTransition(from, action, to, failureState);
        }
    }

    private record StateActionKey<S>(S state, ActionBinding<?> action) {
        private StateActionKey {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(action, "action");
        }
    }
}
