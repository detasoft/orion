package pro.deta.orion.git.nativestorage.pack;

import pro.deta.orion.git.common.GitObjectId;

import java.util.List;
import java.util.Objects;

public record PackPublicationRequest(
        byte[] packBytes,
        byte[] indexBytes,
        String packId,
        String indexId,
        int objectCount,
        List<GitObjectId> objectIds) {
    public PackPublicationRequest {
        packBytes = Objects.requireNonNull(packBytes, "packBytes").clone();
        indexBytes = Objects.requireNonNull(indexBytes, "indexBytes").clone();
        packId = requireNonBlank(packId, "packId");
        indexId = requireNonBlank(indexId, "indexId");
        if (objectCount < 0) {
            throw new IllegalArgumentException("objectCount must be nonnegative");
        }
        objectIds = List.copyOf(Objects.requireNonNull(objectIds, "objectIds"));
        if (objectIds.size() != objectCount) {
            throw new IllegalArgumentException("objectIds size must match objectCount");
        }
    }

    @Override
    public byte[] packBytes() {
        return packBytes.clone();
    }

    @Override
    public byte[] indexBytes() {
        return indexBytes.clone();
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
