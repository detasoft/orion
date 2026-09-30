package pro.deta.orion.acl.storage;

import pro.deta.orion.util.Result;
import pro.deta.orion.internal.UserEmail;

import java.util.Objects;
import java.util.function.Consumer;

public interface AccessControlStorage {
    Result<AccessControlSnapshot> load();

    void save(AccessControlSnapshot snapshot, String message, UserEmail author);

    String primaryPath();

    default boolean createIfMissing() {
        return false;
    }

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
