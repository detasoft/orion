package pro.deta.orion.agentd.session;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class SessionRegistry {
    private final AtomicReference<DiscoverySnapshot> snapshot =
            new AtomicReference<>(DiscoverySnapshot.empty());

    public DiscoverySnapshot snapshot() {
        return snapshot.get();
    }

    void replace(DiscoverySnapshot next) {
        snapshot.set(Objects.requireNonNull(next, "next"));
    }
}
