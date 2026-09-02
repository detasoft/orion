package pro.deta.orion.agent.protocol;

public record AgentProtocolVersion(int value) {
    public static final AgentProtocolVersion CURRENT = new AgentProtocolVersion(1);

    public AgentProtocolVersion {
        value = ProtocolValidation.unsignedShort(value, "agentProtocolVersion");
        if (value == 0) {
            throw new IllegalArgumentException("agentProtocolVersion must be positive");
        }
    }
}
