package pro.deta.orion.provisioning;

public record AgentdReplacementResult(
        State state,
        AgentdProcessIdentity identity,
        ProvisioningResult provisioning
) {
    public AgentdReplacementResult {
        if (state == null || identity == null || provisioning == null) {
            throw new IllegalArgumentException("AgentD replacement result fields must not be null");
        }
    }

    public enum State {
        LAUNCHED,
        ADOPTED
    }
}
