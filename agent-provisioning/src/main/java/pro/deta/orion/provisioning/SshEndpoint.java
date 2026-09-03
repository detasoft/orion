package pro.deta.orion.provisioning;

import java.security.PublicKey;

public record SshEndpoint(String host, int port, String username, PublicKey expectedHostKey) {
    public SshEndpoint {
        host = requireToken(host, "SSH host", false);
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("SSH port must be between 1 and 65535");
        }
        username = requireToken(username, "SSH username", true);
        if (expectedHostKey == null) {
            throw new IllegalArgumentException("Expected SSH host key must not be null");
        }
    }

    private static String requireToken(String value, String name, boolean rejectWhitespace) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                || rejectWhitespace && value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }
}
