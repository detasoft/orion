package pro.deta.orion.lifecycle.state;

import java.util.Objects;

/**
 * Typed executable binding for one lifecycle action in one machine state.
 *
 * <p>The binding instance is the precise transition key for low-level execution. The binding id is the semantic action
 * key used by {@link StateMachine#execute(ActionId, Object)} to select and continue transitions across states.</p>
 */
public final class ActionBinding<A> {
    private final ActionId id;
    private final LifecycleActionHandler<A> handler;
    private volatile StateMachine stateMachine;

    private ActionBinding(ActionId id, LifecycleActionHandler<A> handler) {
        this.id = Objects.requireNonNull(id, "id");
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    public static <A> ActionBinding<A> of(String id, LifecycleActionHandler<A> handler) {
        return of(ActionId.of(id), handler);
    }

    public static <A> ActionBinding<A> of(ActionId id, LifecycleActionHandler<A> handler) {
        return new ActionBinding<>(id, handler);
    }

    public ActionId id() {
        return id;
    }

    public StateMachine stateMachine() {
        StateMachine registered = stateMachine;
        if (registered == null) {
            throw new IllegalStateException("Action " + id + " is not registered in a state machine");
        }
        return registered;
    }

    void register(StateMachine stateMachine) {
        Objects.requireNonNull(stateMachine, "stateMachine");
        if (this.stateMachine == null) {
            this.stateMachine = stateMachine;
            return;
        }
        if (this.stateMachine != stateMachine) {
            throw new IllegalStateException("Action " + id + " is already registered in another state machine");
        }
    }

    Object execute(A payload) throws Exception {
        return handler.execute(payload);
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
