package pro.deta.orion.agent.protocol;

import java.util.Objects;

/** A decoded Agent message together with its unchanged CBOR Sequence item. */
public record AgentMessageRecord(AgentMessage message, ProtocolBytes encodedItem) {
    public AgentMessageRecord {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(encodedItem, "encodedItem");
    }
}
