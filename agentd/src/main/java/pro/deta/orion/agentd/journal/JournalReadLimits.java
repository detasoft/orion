package pro.deta.orion.agentd.journal;

import pro.deta.orion.agent.protocol.AgentProtocolLimits;

public record JournalReadLimits(int maxRecords, long maxEncodedBytes) {
    public JournalReadLimits {
        if (maxRecords < 1) {
            throw new IllegalArgumentException("maxRecords must be positive");
        }
        if (maxEncodedBytes < AgentProtocolLimits.HARD_MAX_JOURNAL_RECORD_BYTES) {
            throw new IllegalArgumentException(
                    "maxEncodedBytes must fit one maximum journal record");
        }
    }
}
