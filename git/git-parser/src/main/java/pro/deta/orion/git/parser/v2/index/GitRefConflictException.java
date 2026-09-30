package pro.deta.orion.git.parser.v2.index;

import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.id.ObjectId;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/** Ref expectations rejected when opening an access or applying its pending updates. */
public final class GitRefConflictException extends IOException {
    private final RefUpdate update;
    private final Optional<ObjectId> actual;

    public GitRefConflictException(RefUpdate update, Optional<ObjectId> actual) {
        super("Ref " + update.ref().value()
                + ": expected=" + update.expectedOld().map(ObjectId::toHex).orElse("<absent>")
                + ", actual=" + actual.map(ObjectId::toHex).orElse("<absent>")
                + ", requested=" + update.newId().map(ObjectId::toHex).orElse("<delete>"));
        this.update = Objects.requireNonNull(update, "update");
        this.actual = Objects.requireNonNull(actual, "actual");
    }

    public RefUpdate update() {
        return update;
    }

    public Optional<ObjectId> actual() {
        return actual;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + ": " + getMessage();
    }
}
