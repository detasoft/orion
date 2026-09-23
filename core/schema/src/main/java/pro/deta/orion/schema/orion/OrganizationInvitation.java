package pro.deta.orion.schema.orion;

import java.util.Locale;

/** Email-bound invitation; only the SHA-256 digest of its random bearer token is persisted. */
public record OrganizationInvitation(String tokenHash, String email, long expiresAt) {
    public OrganizationInvitation {
        if (tokenHash == null || !tokenHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid invitation digest");
        }
        email = normalizeEmail(email);
        if (expiresAt <= 0) {
            throw new IllegalArgumentException("Invalid invitation expiration");
        }
    }

    public static String normalizeEmail(String email) {
        if (email == null || email.length() > 254) {
            throw new IllegalArgumentException("Invalid email");
        }
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw new IllegalArgumentException("Invalid email");
        }
        return normalized;
    }
}
