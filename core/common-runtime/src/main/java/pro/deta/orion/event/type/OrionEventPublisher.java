package pro.deta.orion.event.type;

/** Publishes an event when the application event system is running. */
@FunctionalInterface
public interface OrionEventPublisher {
    void publishIfRunning(OrionEvent event);
}
