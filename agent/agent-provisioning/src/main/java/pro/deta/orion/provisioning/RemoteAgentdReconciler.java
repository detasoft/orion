package pro.deta.orion.provisioning;

public final class RemoteAgentdReconciler implements AgentdReplacement {
    private final RemoteAgentdProvisioner provisioner;
    private final AgentdRecoveryOptions options;

    public RemoteAgentdReconciler(RemoteAgentdProvisioner provisioner, AgentdRecoveryOptions options) {
        if (provisioner == null || options == null) {
            throw new IllegalArgumentException("Remote AgentD reconciler arguments must not be null");
        }
        this.provisioner = provisioner;
        this.options = options;
    }

    @Override
    public AgentdReplacementResult reconcile(
            AgentdLaunchAttempt attempt,
            AgentdProcessIdentity previous) throws ProvisioningException, InterruptedException {
        return provisioner.reconcile(attempt, previous, options);
    }

    @Override
    public AgentdReplacementResult recoverPartial(
            AgentdLaunchAttempt attempt,
            AgentdProcessIdentity partial) throws ProvisioningException, InterruptedException {
        return provisioner.recoverPartial(attempt, partial, options);
    }
}
