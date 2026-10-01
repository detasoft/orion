package pro.deta.orion.agentd.sandbox;

public final class PolicyException extends RuntimeException {
    public PolicyException(String message) {
        super(message);
    }

    public PolicyException(String message, Throwable cause) {
        super(message, cause);
    }
}
