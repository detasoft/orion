package pro.deta.orion.event;

import pro.deta.orion.event.type.OrionEvent;

import java.util.function.Consumer;

public interface OrionEvents {
    <T extends OrionEvent> EventSubscription subscribe(Class<T> type, Consumer<T> consumer);

    void publish(OrionEvent event);
}
