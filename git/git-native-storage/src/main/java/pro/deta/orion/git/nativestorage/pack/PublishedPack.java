package pro.deta.orion.git.nativestorage.pack;

import java.util.Objects;

public record PublishedPack(
        String packId,
        long packBytes,
        int objectCount,
        String packChecksum,
        String indexChecksum) {
    public PublishedPack {
        packId = requireNonBlank(packId, "packId");
        packChecksum = requireNonBlank(packChecksum, "packChecksum");
        indexChecksum = requireNonBlank(indexChecksum, "indexChecksum");
        if (packBytes < 0) {
            throw new IllegalArgumentException("packBytes must be nonnegative");
        }
        if (objectCount < 0) {
            throw new IllegalArgumentException("objectCount must be nonnegative");
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
