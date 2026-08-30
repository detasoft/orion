package pro.deta.orion.git.nativestorage.pack;

import pro.deta.orion.git.nativestorage.GitObjectId;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Manifest for durable pack bytes that may later be served by URI. A
 * self-contained pack has every object needed to inflate its deltas inside the
 * same pack. A non-self-contained, or thin, pack depends on external base
 * objects that were already present in the repository at receive time.
 */
public record PublishedPackManifest(
        String packId,
        long packBytes,
        int objectCount,
        String packChecksum,
        String indexChecksum,
        boolean selfContained,
        Set<GitObjectId> objectIds,
        Set<GitObjectId> externalBaseIds) {
    public PublishedPackManifest {
        packId = requireNonBlank(packId, "packId");
        packChecksum = requireNonBlank(packChecksum, "packChecksum");
        indexChecksum = requireNonBlank(indexChecksum, "indexChecksum");
        if (packBytes < 0) {
            throw new IllegalArgumentException("packBytes must be nonnegative");
        }
        if (objectCount < 0) {
            throw new IllegalArgumentException("objectCount must be nonnegative");
        }
        objectIds = Collections.unmodifiableSet(
                new LinkedHashSet<>(Objects.requireNonNull(
                        objectIds,
                        "objectIds")));
        externalBaseIds = Collections.unmodifiableSet(
                new LinkedHashSet<>(Objects.requireNonNull(
                        externalBaseIds,
                        "externalBaseIds")));
        if (objectIds.size() != objectCount) {
            throw new IllegalArgumentException(
                    "objectIds size must match objectCount");
        }
        if (selfContained && !externalBaseIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "selfContained pack must not declare external bases");
        }
    }

    public PublishedPack publishedPack() {
        return new PublishedPack(
                packId,
                packBytes,
                objectCount,
                packChecksum,
                indexChecksum);
    }

    private static String requireNonBlank(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
