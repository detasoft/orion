package pro.deta.orion.event;

import pro.deta.orion.event.type.OrionEvent;

import java.util.Objects;
import java.util.function.Consumer;

public final class OrionEvents {
    private final OrionEventManager manager;
    private final Object target;

    OrionEvents(OrionEventManager manager, Object target) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.target = Objects.requireNonNull(target, "target");
    }

    public void publish(OrionEvent event) {
        manager.publish(event);
    }

    public <T extends OrionEvent> void subscribe(Class<T> type, Object source, Consumer<? super T> handler) {
        manager.subscribe(type, source, target, handler);
    }

    public void unsubscribe(Object source) {
        manager.unsubscribe(source, target);
    }

    public void unsubscribe(Class<? extends OrionEvent> type, Object source) {
        manager.unsubscribe(type, source, target);
    }
}
