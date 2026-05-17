package pro.deta.orion.lifecycle.state;

import java.util.Objects;

public final class ActionBinding<A> {
    private final String id;
    private final LifecycleActionHandler<A> handler;

    private ActionBinding(String id, LifecycleActionHandler<A> handler) {
        this.id = Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Action id must not be blank");
        }
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    public static <A> ActionBinding<A> of(String id, LifecycleActionHandler<A> handler) {
        return new ActionBinding<>(id, handler);
    }

    public String id() {
        return id;
    }

    void execute(A payload) throws Exception {
        handler.execute(payload);
    }

    @Override
    public String toString() {
        return id;
    }
}
