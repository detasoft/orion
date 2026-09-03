package pro.deta.orion.provisioning;

import java.util.Objects;

final class AgentdPartialLaunchException extends ProvisioningException {
    private final AgentdProcessIdentity identity;

    AgentdPartialLaunchException(ProvisioningFailure failure, String message,
            AgentdProcessIdentity identity, Throwable cause) {
        super(failure, message, cause);
        this.identity = Objects.requireNonNull(identity, "AgentD partial launch identity must not be null");
    }

    AgentdProcessIdentity identity() {
        return identity;
    }
}
