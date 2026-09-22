package pro.deta.orion.schema.orion;

/**
 * Credential formats shared by Git configuration and client transports.
 */
public enum GitCredentialKind {
    NONE, PASSWORD, TOKEN, PRIVATE_KEY;

    public void requireTransport(String scheme) {
        boolean matches = switch (scheme) {
            case "file" -> this == NONE;
            case "http", "https" -> this == PASSWORD || this == TOKEN;
            case "ssh" -> this == PASSWORD || this == PRIVATE_KEY;
            default -> false;
        };
        if (!matches) {
            throw new IllegalArgumentException("Remote Git credential kind does not match transport");
        }
    }
}
