package pro.deta.orion.agent.protocol;

public record AgentGeneration(long value) {
    public AgentGeneration {
        if (value <= 0) {
            throw new IllegalArgumentException("agent generation must be positive");
        }
    }
}
