package pro.deta.orion.git.nativestorage.pack;

public record PackIngestionLimits(
        long maxPackBytes,
        int maxObjectCount,
        int maxInflatedObjectBytes) {
    public PackIngestionLimits {
        if (maxPackBytes < 1) {
            throw new IllegalArgumentException(
                    "maxPackBytes must be positive");
        }
        if (maxObjectCount < 1) {
            throw new IllegalArgumentException(
                    "maxObjectCount must be positive");
        }
        if (maxInflatedObjectBytes < 1) {
            throw new IllegalArgumentException(
                    "maxInflatedObjectBytes must be positive");
        }
    }
}
