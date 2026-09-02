package pro.deta.orion.agentd.protocol;

public record AgentProtocolLimits(
        int maxFrameBytes,
        int maxFieldCount,
        int maxCollectionEntries,
        int maxStringBytes,
        int maxBinaryBytes
) {
    public static final int HARD_MAX_FRAME_BYTES = 16 * 1024 * 1024;
    public static final int DEFAULT_MAX_FRAME_BYTES = HARD_MAX_FRAME_BYTES;
    private static final int FRAME_HEADER_BYTES = 16;

    public AgentProtocolLimits {
        if (maxFrameBytes < FRAME_HEADER_BYTES || maxFrameBytes > HARD_MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("maxFrameBytes must be between 16 and 16777216");
        }
        if (maxFieldCount < 1 || maxCollectionEntries < 1) {
            throw new IllegalArgumentException("field and collection limits must be positive");
        }
        int maxPayloadBytes = maxFrameBytes - FRAME_HEADER_BYTES;
        if (maxStringBytes < 0 || maxStringBytes > maxPayloadBytes) {
            throw new IllegalArgumentException("maxStringBytes must fit in a frame payload");
        }
        if (maxBinaryBytes < 0 || maxBinaryBytes > maxPayloadBytes) {
            throw new IllegalArgumentException("maxBinaryBytes must fit in a frame payload");
        }
    }

    public static AgentProtocolLimits defaults() {
        return new AgentProtocolLimits(DEFAULT_MAX_FRAME_BYTES, 4_096, 1_024, 256 * 1024,
                DEFAULT_MAX_FRAME_BYTES - FRAME_HEADER_BYTES);
    }

    public AgentProtocolLimits withMaxFrameBytes(int value) {
        int maxPayloadBytes = value - FRAME_HEADER_BYTES;
        return new AgentProtocolLimits(
                value,
                maxFieldCount,
                maxCollectionEntries,
                Math.min(maxStringBytes, maxPayloadBytes),
                Math.min(maxBinaryBytes, maxPayloadBytes));
    }
}
