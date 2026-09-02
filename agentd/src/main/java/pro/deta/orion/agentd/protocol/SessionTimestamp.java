package pro.deta.orion.agentd.protocol;

public record SessionTimestamp(long value) {
    public SessionTimestamp {
        if (value <= 0) {
            throw new IllegalArgumentException("Session timestamp must be positive");
        }
    }
}
