package pro.deta.orion.git.nativestorage.object;

import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;

import java.util.Objects;

public record LooseObject(
        ObjectId id,
        GitObjectType type,
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
