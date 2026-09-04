package pro.deta.orion.agent.protocol;

public record AgentProtocolLimits(
        int maxMessageBytes,
        int maxCollectionEntries,
        int maxStringBytes,
        int maxBinaryBytes,
        int maxNestingDepth
) {
    public static final int HARD_MAX_MESSAGE_BYTES = 16 * 1024 * 1024;
    public static final int HARD_MAX_JOURNAL_RECORD_BYTES = HARD_MAX_MESSAGE_BYTES + 4 * 1024;
    public static final int DEFAULT_MAX_MESSAGE_BYTES = HARD_MAX_MESSAGE_BYTES;
    public static final int DEFAULT_MAX_FRAME_BYTES = DEFAULT_MAX_MESSAGE_BYTES;

    public AgentProtocolLimits {
        if (maxMessageBytes < 1 || maxMessageBytes > HARD_MAX_JOURNAL_RECORD_BYTES) {
            throw new IllegalArgumentException(
                    "maxMessageBytes must be between 1 and " + HARD_MAX_JOURNAL_RECORD_BYTES);
        }
        if (maxCollectionEntries < 1) {
            throw new IllegalArgumentException("maxCollectionEntries must be positive");
        }
        if (maxStringBytes < 0 || maxStringBytes > maxMessageBytes) {
            throw new IllegalArgumentException("maxStringBytes must fit in a message");
        }
        if (maxBinaryBytes < 0 || maxBinaryBytes > maxMessageBytes) {
            throw new IllegalArgumentException("maxBinaryBytes must fit in a message");
        }
        if (maxNestingDepth < 1 || maxNestingDepth > 256) {
            throw new IllegalArgumentException("maxNestingDepth must be between 1 and 256");
        }
    }

    public static AgentProtocolLimits defaults() {
        return new AgentProtocolLimits(
                DEFAULT_MAX_MESSAGE_BYTES,
                1_024,
                256 * 1024,
                DEFAULT_MAX_MESSAGE_BYTES,
                64);
    }

    public static AgentProtocolLimits journalDefaults() {
        return new AgentProtocolLimits(
                HARD_MAX_JOURNAL_RECORD_BYTES,
                1_024,
                256 * 1024,
                DEFAULT_MAX_MESSAGE_BYTES,
                64);
    }

    public AgentProtocolLimits withMaxMessageBytes(int value) {
        return new AgentProtocolLimits(
                value,
                maxCollectionEntries,
                Math.min(maxStringBytes, value),
                Math.min(maxBinaryBytes, value),
                maxNestingDepth);
    }

    public AgentProtocolLimits withMaxFrameBytes(int value) {
        if (value < 1 || value > HARD_MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("maxFrameBytes must be between 1 and 16777216");
        }
        return withMaxMessageBytes(value);
    }

    public int maxFrameBytes() {
        return Math.min(maxMessageBytes, HARD_MAX_MESSAGE_BYTES);
    }

    AgentProtocolLimits agentMessageLimits() {
        int frameBytes = maxFrameBytes();
        return frameBytes == maxMessageBytes ? this : withMaxMessageBytes(frameBytes);
    }
}
