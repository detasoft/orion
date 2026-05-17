package pro.deta.orion.lifecycle.state;

import java.util.Objects;

/**
 * Typed executable binding for one lifecycle action in one machine state.
 *
 * <p>The binding instance is the precise transition key for low-level execution, so
 * {@link StateMachine#execute(ActionBinding, Object)} runs one concrete transition. The binding id is the semantic
 * action key used by {@link StateMachine#execute(ActionId, Object)} to select and continue transitions across states.</p>
 */
public final class ActionBinding<A> {
    private final ActionId id;
    private final LifecycleActionHandler<A> handler;

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

    void execute(A payload) throws Exception {
        handler.execute(payload);
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
