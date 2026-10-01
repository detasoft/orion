package pro.deta.orion.agent.protocol;

public record JournalFormatVersion(int value) {
    public static final JournalFormatVersion CURRENT = new JournalFormatVersion(1);

    public JournalFormatVersion {
        value = ProtocolValidation.unsignedShort(value, "journalFormatVersion");
        if (value == 0) {
            throw new IllegalArgumentException("journalFormatVersion must be positive");
        }
    }
}
