package pro.deta.orion.agent.protocol;

import java.util.Objects;

public sealed interface SessionReplicationItem
        permits SessionReplicationItem.Open, SessionReplicationItem.Event {

    record Open(AgentMessage.SessionOpen message) implements SessionReplicationItem {
        public Open {
            Objects.requireNonNull(message, "message");
        }
    }

    record Event(SessionEventRecord record) implements SessionReplicationItem {
        public Event {
            Objects.requireNonNull(record, "record");
        }
    }
}
