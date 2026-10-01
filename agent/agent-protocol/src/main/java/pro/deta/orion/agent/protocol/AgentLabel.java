package pro.deta.orion.agent.protocol;

public record AgentLabel(String value) {
    public AgentLabel {
        value = ProtocolValidation.identifier(value, "agentLabel");
    }
}
