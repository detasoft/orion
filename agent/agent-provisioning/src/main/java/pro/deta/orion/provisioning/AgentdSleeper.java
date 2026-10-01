package pro.deta.orion.provisioning;

import java.time.Duration;

@FunctionalInterface
public interface AgentdSleeper {
    void sleep(Duration duration) throws InterruptedException;
}
