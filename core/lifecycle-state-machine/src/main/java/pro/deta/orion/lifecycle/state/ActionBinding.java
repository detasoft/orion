package pro.deta.orion.lifecycle.state;

import java.util.Objects;

public final class ActionBinding<A> {
    private final ActionId id;
    private final Class<A> payloadType;
    private final LifecycleActionHandler<A> handler;

    private ActionBinding(ActionId id, Class<A> payloadType, LifecycleActionHandler<A> handler) {
        this.id = Objects.requireNonNull(id, "id");
        this.payloadType = Objects.requireNonNull(payloadType, "payloadType");
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    public static <A> ActionBinding<A> of(Class<A> payloadType, LifecycleActionHandler<A> handler) {
        return of(ActionId.of(payloadType), payloadType, handler);
    }

    public static <A> ActionBinding<A> of(String id, Class<A> payloadType, LifecycleActionHandler<A> handler) {
        return of(new ActionId(id), payloadType, handler);
    }

    public static <A> ActionBinding<A> of(ActionId id, Class<A> payloadType, LifecycleActionHandler<A> handler) {
        return new ActionBinding<>(id, payloadType, handler);
    }

    public ActionId id() {
        return id;
    }

    public Class<A> payloadType() {
        return payloadType;
    }

    void execute(Object payload) throws Exception {
        handler.execute(payloadType.cast(payload));
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
