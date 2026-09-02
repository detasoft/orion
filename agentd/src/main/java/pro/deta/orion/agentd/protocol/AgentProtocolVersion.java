package pro.deta.orion.agentd.protocol;

public record AgentProtocolVersion(int value) {
    public static final AgentProtocolVersion CURRENT = new AgentProtocolVersion(1);

    public AgentProtocolVersion {
        if (value < 1 || value > 0xffff) {
            throw new IllegalArgumentException("Agent protocol version must be between 1 and 65535");
        }
    }
}
