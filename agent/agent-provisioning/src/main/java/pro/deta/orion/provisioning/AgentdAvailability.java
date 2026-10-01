package pro.deta.orion.provisioning;

import pro.deta.orion.agent.protocol.AgentLaunchId;

import java.time.Duration;

public interface AgentdAvailability {
    boolean awaitSustainedOffline(Duration timeout) throws InterruptedException;

    boolean awaitOnline(AgentLaunchId launchId, Duration timeout) throws InterruptedException;
}
