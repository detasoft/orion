package pro.deta.orion.agentd.core;

import java.io.IOException;

public final class AgentAlreadyRunningException extends IOException {
    public AgentAlreadyRunningException() {
        super("Another AgentD process already holds the state-directory lock");
    }
}
