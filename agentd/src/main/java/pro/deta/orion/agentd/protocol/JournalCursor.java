package pro.deta.orion.agentd.protocol;

public record JournalCursor(long timestamp) {
    public static final JournalCursor BEFORE_FIRST = new JournalCursor(0);

    public JournalCursor {
        if (timestamp < 0) {
            throw new IllegalArgumentException("Journal cursor timestamp must not be negative");
        }
    }
}
