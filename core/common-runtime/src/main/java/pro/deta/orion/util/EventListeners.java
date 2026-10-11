package pro.deta.orion.util;

import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Holds listeners for synchronous delivery; the owner publishes events and closes the registry. */
@Slf4j
public final class EventListeners<T> implements AutoCloseable {
    private final CopyOnWriteArrayList<Consumer<T>> listeners = new CopyOnWriteArrayList<>();
    private boolean closed;

    public synchronized Subscription subscribe(Consumer<T> listener) {
        Objects.requireNonNull(listener, "listener");
        if (closed) {
            throw new IllegalStateException("Event listeners are closed");
        }
        Consumer<T> registered = listener::accept;
        listeners.add(registered);
        return () -> listeners.remove(registered);
    }

    public void publish(T event) {
        for (Consumer<T> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException failure) {
                log.warn("Event listener failed for {}", event, failure);
            }
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        listeners.clear();
    }
}
