package pro.deta.orion.git.nativestorage.pack;

import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.object.LooseObject;
import pro.deta.orion.git.nativestorage.object.LooseObjectPrefix;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

public interface PackObjectDirectory {
    PackObjectDirectory NONE = id -> Optional.empty();

    Optional<LooseObject> read(GitObjectId id);

    default Optional<LooseObjectPrefix> readPrefix(
            GitObjectId id,
            int maxDataBytes) {
        Objects.requireNonNull(id, "id");
        if (maxDataBytes < 0) {
            throw new IllegalArgumentException(
                    "maxDataBytes must be nonnegative");
        }
        Optional<LooseObject> object = read(id);
        if (object.isEmpty()) {
            return Optional.empty();
        }
        byte[] data = object.get().data();
        int prefixLength = Math.min(data.length, maxDataBytes);
        return Optional.of(new LooseObjectPrefix(
                id,
                object.get().type(),
                data.length,
                Arrays.copyOf(data, prefixLength)));
    }
}
