package pro.deta.orion.agentd.core;

public final class AgentShutdownException extends RuntimeException {
    public AgentShutdownException(String message, Throwable cause) {
        super(message, cause);
    }
}
