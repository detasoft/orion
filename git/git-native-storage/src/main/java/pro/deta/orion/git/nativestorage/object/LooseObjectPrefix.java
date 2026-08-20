package pro.deta.orion.git.nativestorage.object;

import pro.deta.orion.git.common.GitObjectId;

import java.util.Objects;

public record LooseObjectPrefix(
        GitObjectId id,
        ObjectType type,
        long declaredDataLength,
        byte[] dataPrefix) {

    public LooseObjectPrefix {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(dataPrefix, "dataPrefix");
        if (declaredDataLength < 0) {
            throw new IllegalArgumentException(
                    "declaredDataLength must be nonnegative");
        }
        dataPrefix = dataPrefix.clone();
    }

    public byte[] dataPrefix() {
        return dataPrefix.clone();
    }
}
