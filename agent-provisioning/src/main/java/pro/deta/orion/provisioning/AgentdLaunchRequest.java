package pro.deta.orion.provisioning;

import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentLaunchId;

import java.net.URI;

public record AgentdLaunchRequest(
        URI serverUri,
        String stateDirectory,
        AgentId agentId,
        AgentGeneration generation,
        AgentLaunchId launchId,
        int maxFrameBytes,
        String agentVersion
) {
    public AgentdLaunchRequest {
        if (serverUri == null || !"https".equalsIgnoreCase(serverUri.getScheme())
                || serverUri.getHost() == null || serverUri.getUserInfo() != null) {
            throw new IllegalArgumentException(
                    "AgentD server URI must be an absolute HTTPS URI without credentials");
        }
        stateDirectory = requirePath(stateDirectory);
        if (agentId == null || generation == null || launchId == null) {
            throw new IllegalArgumentException("AgentD launch identity must not be null");
        }
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("AgentD maximum frame bytes must be positive");
        }
        if (agentVersion == null || agentVersion.isBlank() || containsControl(agentVersion)) {
            throw new IllegalArgumentException("AgentD version is invalid");
        }
        serverUri = serverUri.normalize();
    }

    private static String requirePath(String value) {
        if (value == null || !value.startsWith("/") || containsControl(value)) {
            throw new IllegalArgumentException("AgentD state directory must be an absolute remote path");
        }
        return value;
    }

    private static boolean containsControl(String value) {
        return value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }
}
