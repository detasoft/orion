package pro.deta.orion.lifecycle.state;

import java.util.List;
import java.util.Objects;

/**
 * Result of a state transition action and final state resolution.
 */
public final class StateTransitionResult {
    private final StateMachineDefinition.State from;
    private final ActionId action;
    private final Object payload;
    private final List<StateMachineDefinition.State> targets;
    private final Object actionResult;
    private final Throwable failure;
    private final StateMachineDefinition.State to;

    public StateTransitionResult(
            StateMachineDefinition.State from,
            ActionId action,
            Object payload,
            List<StateMachineDefinition.State> targets,
            Object actionResult,
            Throwable failure,
            StateMachineDefinition.State to) {
        this.from = Objects.requireNonNull(from, "from");
        this.action = Objects.requireNonNull(action, "action");
        this.payload = payload;
        this.targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        this.actionResult = actionResult;
        this.failure = failure;
        this.to = to;
    }

    public StateTransitionResult(
            StateMachineDefinition.State from,
            ActionId action,
            Object payload,
            StateMachineDefinition.State to,
            Throwable failure) {
        this(from, action, payload, List.of(Objects.requireNonNull(to, "to")), null, failure, to);
    }

    public StateMachineDefinition.State from() {
        return from;
    }

    public ActionId action() {
        return action;
    }

    public Object payload() {
        return payload;
    }

    public List<StateMachineDefinition.State> targets() {
        return targets;
    }

    public Object actionResult() {
        return actionResult;
    }

    public Throwable failure() {
        return failure;
    }

    public StateMachineDefinition.State to() {
        return to;
    }

    public StateTransitionResult withTo(StateMachineDefinition.State selectedState) {
        return new StateTransitionResult(
                from,
                action,
                payload,
                targets,
                actionResult,
                failure,
                Objects.requireNonNull(selectedState, "selectedState"));
    }

    public StateMachineDefinition.State defaultState() {
        return StateTransitionResolvers.defaultState(this);
    }

    public boolean failed() {
        return failure != null;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("StateTransitionResult[");
        builder.append("from=").append(from);
        builder.append(", action=").append(action);
        if (payload != Void.EMPTY) {
            builder.append(", payload=").append(payload);
        }
        if (actionResult != null && actionResult != Void.EMPTY) {
            builder.append(", actionResult=").append(actionResult);
        }
        if (to != null) {
            builder.append(", to=").append(to);
        }
        if (failure != null) {
            builder.append(", failure=").append(failure);
        }
        return builder.append(']').toString();
    }
}
