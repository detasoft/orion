package pro.deta.orion.git.parser.wire.capability;

/**
 * Object hash formats named by the object-format capability. wireName supplies the protocol spelling.
 * Identifying a format here does not imply that every command or object storage implementation supports it.
 */
public enum GitObjectFormat {
    SHA1("sha1"),
    SHA256("sha256");

    private final String wireName;

    GitObjectFormat(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
