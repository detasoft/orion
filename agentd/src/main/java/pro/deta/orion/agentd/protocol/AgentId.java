package pro.deta.orion.agentd.protocol;

public record AgentId(String value) {
    public AgentId {
        value = ProtocolValidation.identifier(value, "agentId");
    }
}
