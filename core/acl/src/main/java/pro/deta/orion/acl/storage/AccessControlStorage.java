package pro.deta.orion.acl.storage;

import pro.deta.orion.util.Result;

import java.util.Objects;
import java.util.function.Consumer;

public interface AccessControlStorage {
    Result<AccessControlSnapshot> load();

    void save(AccessControlSnapshot snapshot, AccessControlSaveRequest request);

    String primaryPath();

    default ChangeSubscription onChange(Consumer<String> listener) {
        Objects.requireNonNull(listener, "listener");
        return () -> {
        };
    }

    @FunctionalInterface
    interface ChangeSubscription extends AutoCloseable {
        @Override
        void close();
    }
}
