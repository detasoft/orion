package pro.deta.orion.event;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.event.type.OrionEvent;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Singleton
@Slf4j
public final class OrionEventRegistry {
    private final ConcurrentHashMap<Route, ConcurrentHashMap<Consumer<?>, Consumer<OrionEvent>>> registrations
            = new ConcurrentHashMap<>();

    @Inject
    public OrionEventRegistry() {}

    public <T extends OrionEvent> void subscribe(Class<T> type, Object source, Consumer<? super T> target) {
        Objects.requireNonNull(target, "target");
        registrations.compute(new Route(type, source), (route, targets) -> {
            if (targets == null) targets = new ConcurrentHashMap<>();
            targets.putIfAbsent(target, event -> target.accept(type.cast(event)));
            return targets;
        });
    }

    public void unsubscribe(Object source, Consumer<?> target) {
        for (Route route : registrations.keySet()) {
            if (Objects.equals(route.source(), source)) removeTarget(route, target);
        }
    }

    public <T extends OrionEvent> void unsubscribe(Class<T> type, Object source, Consumer<? super T> target) {
        removeTarget(new Route(type, source), target);
    }

    private void removeTarget(Route route, Consumer<?> target) {
        registrations.computeIfPresent(route, (key, targets) -> {
            targets.remove(target);
            return targets.isEmpty() ? null : targets;
        });
    }

    void dispatch(OrionEvent event) {
        ConcurrentHashMap<Consumer<?>, Consumer<OrionEvent>> targets = registrations.get(
                new Route(event.getClass(), event.source()));
        if (targets == null) return;
        for (Consumer<OrionEvent> target : targets.values()) {
            try {
                target.accept(event);
            } catch (RuntimeException failure) {
                log.warn("Event handler failed for {}", event, failure);
            }
        }
    }

    private record Route(Class<? extends OrionEvent> type, Object source) {
        private Route {
            Objects.requireNonNull(type, "type");
        }
    }
}
