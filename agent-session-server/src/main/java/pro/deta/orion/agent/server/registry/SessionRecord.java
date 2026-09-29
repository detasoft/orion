package pro.deta.orion.agent.server.registry;

import pro.deta.orion.agent.protocol.AgentLabel;
import pro.deta.orion.agent.protocol.SessionDescriptor;

import java.util.Objects;

public record SessionRecord(
        AgentLabel agentLabel,
        SessionDescriptor descriptor) {
    public SessionRecord {
        Objects.requireNonNull(agentLabel, "agentLabel");
        Objects.requireNonNull(descriptor, "descriptor");
    }
}
