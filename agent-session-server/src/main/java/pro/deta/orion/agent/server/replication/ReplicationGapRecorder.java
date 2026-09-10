package pro.deta.orion.agent.server.replication;

import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.SessionId;
import pro.deta.orion.agent.server.journal.JournalGap;

@FunctionalInterface
public interface ReplicationGapRecorder {
    void record(AgentId agentId, SessionId sessionId, JournalGap gap) throws GapRecordingException;
}
