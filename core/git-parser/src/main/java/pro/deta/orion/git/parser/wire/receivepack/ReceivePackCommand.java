package pro.deta.orion.git.parser.wire.receivepack;

import java.util.Objects;

public record ReceivePackCommand(
        String oldId,
        String newId,
        String refName) {

    static final String NULL_ID = "0".repeat(40);

    public ReceivePackCommand {
        Objects.requireNonNull(oldId, "oldId");
        Objects.requireNonNull(newId, "newId");
        Objects.requireNonNull(refName, "refName");
    }

    public boolean isCreate() {
        return NULL_ID.equals(oldId) && !isDelete();
    }

    public boolean isUpdate() {
        return !NULL_ID.equals(oldId) && !isDelete();
    }

    public boolean isDelete() {
        return NULL_ID.equals(newId);
    }
}
