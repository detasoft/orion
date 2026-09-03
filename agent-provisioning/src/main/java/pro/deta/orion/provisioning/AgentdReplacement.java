package pro.deta.orion.provisioning;

@FunctionalInterface
public interface AgentdReplacement {
    AgentdReplacementResult reconcile(
            AgentdLaunchAttempt attempt,
            AgentdProcessIdentity previous) throws ProvisioningException, InterruptedException;

    default AgentdReplacementResult recoverPartial(
            AgentdLaunchAttempt attempt,
            AgentdProcessIdentity partial) throws ProvisioningException, InterruptedException {
        throw new ProvisioningException(
                ProvisioningFailure.UNCERTAIN_IDENTITY,
                "AgentD replacement does not support safe partial-launch recovery");
    }
}
