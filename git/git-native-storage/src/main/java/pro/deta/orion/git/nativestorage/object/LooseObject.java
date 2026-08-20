package pro.deta.orion.git.nativestorage.object;

import pro.deta.orion.git.common.GitObjectId;

import java.util.Objects;

public record LooseObject(
        GitObjectId id,
        ObjectType type,
        byte[] data) {

    public LooseObject {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(data, "data");
        data = data.clone();
    }

    public byte[] data() {
        return data.clone();
    }
}
