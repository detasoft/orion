package pro.deta.orion.git.nativestorage.pack;

public final class PackParseException extends RuntimeException {
    public enum Kind {
        INCOMPLETE,
        MALFORMED,
        LIMIT_EXCEEDED
    }

    private final Kind kind;

    public PackParseException(String message) {
        this(Kind.MALFORMED, message);
    }

    public PackParseException(Kind kind, String message) {
        super(message);
        this.kind = java.util.Objects.requireNonNull(kind, "kind");
    }

    public Kind kind() {
        return kind;
    }
}
