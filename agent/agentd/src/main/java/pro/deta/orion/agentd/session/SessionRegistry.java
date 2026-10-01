package pro.deta.orion.agentd.session;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

public final class SessionRegistry {
    private final AtomicReference<DiscoverySnapshot> snapshot =
            new AtomicReference<>(DiscoverySnapshot.empty());
    private final CompletableFuture<DiscoverySnapshot> ready = new CompletableFuture<>();
    private final List<BiConsumer<DiscoverySnapshot, DiscoverySnapshot>> observers =
            new CopyOnWriteArrayList<>();

    public DiscoverySnapshot snapshot() {
        return snapshot.get();
    }

    public CompletionStage<DiscoverySnapshot> readySnapshot() {
        return ready.copy();
    }

    public Observation observe(BiConsumer<DiscoverySnapshot, DiscoverySnapshot> observer) {
        BiConsumer<DiscoverySnapshot, DiscoverySnapshot> registered =
                Objects.requireNonNull(observer, "observer");
        observers.add(registered);
        return () -> observers.remove(registered);
    }

    synchronized void replace(DiscoverySnapshot next) {
        Objects.requireNonNull(next, "next");
        DiscoverySnapshot previous = snapshot.getAndSet(next);
        if (!ready.isDone()) {
            ready.complete(next);
            return;
        }
        if (previous.equals(next)) {
            return;
        }
        for (BiConsumer<DiscoverySnapshot, DiscoverySnapshot> observer : observers) {
            observer.accept(previous, next);
        }
    }

    @FunctionalInterface
    public interface Observation extends AutoCloseable {
        @Override
        void close();
    }
}
