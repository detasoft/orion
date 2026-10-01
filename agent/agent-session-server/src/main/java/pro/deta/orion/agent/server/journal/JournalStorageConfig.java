package pro.deta.orion.agent.server.journal;

import pro.deta.orion.agent.protocol.AgentProtocolLimits;

import java.util.Objects;

public record JournalStorageConfig(
        AgentProtocolLimits protocolLimits,
        int maxAppendRecords,
        int maxAppendBytes,
        long targetSegmentBytes,
        long maxLogicalSegmentBytes,
        long maxZstdWindowBytes) {
    public static final long DEFAULT_MAX_LOGICAL_SEGMENT_BYTES = 64L * 1024 * 1024;
    public static final long DEFAULT_MAX_ZSTD_WINDOW_BYTES = 64L * 1024 * 1024;
    private static final long MAX_LOGICAL_SEGMENT_BYTES = 1024L * 1024 * 1024;
    private static final long MIN_ZSTD_WINDOW_BYTES = 1024;
    private static final long MAX_ZSTD_WINDOW_BYTES = 1L << 31;

    public JournalStorageConfig(AgentProtocolLimits protocolLimits) {
        this(
                protocolLimits,
                defaultRecordLimit(protocolLimits),
                defaultByteLimit(protocolLimits),
                Long.MAX_VALUE,
                defaultLogicalLimit(protocolLimits),
                DEFAULT_MAX_ZSTD_WINDOW_BYTES);
    }

    public JournalStorageConfig(
            AgentProtocolLimits protocolLimits,
            int maxAppendRecords,
            int maxAppendBytes) {
        this(
                protocolLimits,
                maxAppendRecords,
                maxAppendBytes,
                Long.MAX_VALUE,
                defaultLogicalLimit(protocolLimits),
                DEFAULT_MAX_ZSTD_WINDOW_BYTES);
    }

    public JournalStorageConfig(
            AgentProtocolLimits protocolLimits,
            int maxAppendRecords,
            int maxAppendBytes,
            long targetSegmentBytes) {
        this(
                protocolLimits,
                maxAppendRecords,
                maxAppendBytes,
                targetSegmentBytes,
                defaultLogicalLimit(protocolLimits),
                DEFAULT_MAX_ZSTD_WINDOW_BYTES);
    }

    public JournalStorageConfig {
        Objects.requireNonNull(protocolLimits, "protocolLimits");
        if (maxAppendRecords < 1) {
            throw new IllegalArgumentException("maxAppendRecords must be positive");
        }
        if (maxAppendBytes < 1) {
            throw new IllegalArgumentException("maxAppendBytes must be positive");
        }
        if (targetSegmentBytes < 1) {
            throw new IllegalArgumentException("targetSegmentBytes must be positive");
        }
        if (maxLogicalSegmentBytes < protocolLimits.maxMessageBytes()
                || maxLogicalSegmentBytes > MAX_LOGICAL_SEGMENT_BYTES) {
            throw new IllegalArgumentException(
                    "maxLogicalSegmentBytes must allow one maximum-size message and not exceed 1073741824");
        }
        if (maxZstdWindowBytes < MIN_ZSTD_WINDOW_BYTES
                || maxZstdWindowBytes > MAX_ZSTD_WINDOW_BYTES) {
            throw new IllegalArgumentException(
                    "maxZstdWindowBytes must be between 1024 and 2147483648");
        }
    }

    private static int defaultRecordLimit(AgentProtocolLimits limits) {
        return Objects.requireNonNull(limits, "protocolLimits").maxCollectionEntries();
    }

    private static int defaultByteLimit(AgentProtocolLimits limits) {
        return Objects.requireNonNull(limits, "protocolLimits").maxMessageBytes();
    }

    private static long defaultLogicalLimit(AgentProtocolLimits limits) {
        return Math.max(
                DEFAULT_MAX_LOGICAL_SEGMENT_BYTES,
                Objects.requireNonNull(limits, "protocolLimits").maxMessageBytes());
    }
}
