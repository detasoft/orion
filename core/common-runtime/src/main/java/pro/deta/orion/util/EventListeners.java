package pro.deta.orion.util;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Delivers events synchronously to registered listeners and reports their failures to the owner. */
public final class EventListeners<T> {
    private final CopyOnWriteArrayList<Consumer<T>> listeners = new CopyOnWriteArrayList<>();
    private final BiConsumer<T, RuntimeException> onFailure;

    public EventListeners(BiConsumer<T, RuntimeException> onFailure) {
        this.onFailure = Objects.requireNonNull(onFailure, "onFailure");
    }

    public Subscription subscribe(Consumer<T> listener) {
        Objects.requireNonNull(listener, "listener");
        Consumer<T> registered = listener::accept;
        listeners.add(registered);
        return () -> listeners.remove(registered);
    }

    public void publish(T event) {
        for (Consumer<T> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException failure) {
                onFailure.accept(event, failure);
            }
        }
    }
}
