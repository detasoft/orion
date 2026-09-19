package pro.deta.orion.git.parser.v2.data;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

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
