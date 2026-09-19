package pro.deta.orion.git.parser.v2.data;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Object hash formats named by the object-format capability. wireName supplies the protocol spelling.
 * Identifying a format here does not imply that every command or object storage implementation supports it.
 */
public enum GitHashAlgorithm {
    SHA1("sha1"),
    SHA256("sha256");

    private final String wireName;

    GitHashAlgorithm(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public MessageDigest newDigest() {
        String algorithm = switch (this) {
            case SHA1 -> "SHA-1";
            case SHA256 -> "SHA-256";
        };
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("Unavailable Git hash algorithm: " + algorithm, error);
        }
    }
}
