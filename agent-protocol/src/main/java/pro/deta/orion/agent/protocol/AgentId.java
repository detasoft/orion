package pro.deta.orion.agent.protocol;

public record AgentId(String value) {
    public AgentId {
        value = ProtocolValidation.identifier(value, "agentId");
    }
}
