package pro.deta.orion.provisioning;

@FunctionalInterface
public interface AgentdLaunchAttemptSource {
    AgentdLaunchAttempt nextAttempt() throws ProvisioningException;
}
