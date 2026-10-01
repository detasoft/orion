package pro.deta.orion.provisioning;

public final class AgentdLaunchAttempt implements AutoCloseable {
    private final AgentdLaunchRequest request;
    private final ProvisioningLaunchPermit permit;

    public AgentdLaunchAttempt(AgentdLaunchRequest request, ProvisioningLaunchPermit permit) {
        if (request == null || permit == null) {
            throw new IllegalArgumentException("AgentD launch attempt fields must not be null");
        }
        this.request = request;
        this.permit = permit;
    }

    public AgentdLaunchRequest request() {
        return request;
    }

    public ProvisioningLaunchPermit permit() {
        return permit;
    }

    @Override
    public void close() {
        permit.close();
    }

    @Override
    public String toString() {
        return "AgentdLaunchAttempt[request=" + request + ", permit=redacted]";
    }
}
