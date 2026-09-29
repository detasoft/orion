package pro.deta.orion.git.parser.v2.data;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public enum GitHashAlgorithm {
    SHA1("sha1", 20),
    SHA256("sha256", 32);

    private final String wireName;
    private final int byteLength;

    GitHashAlgorithm(String wireName, int byteLength) {
        this.wireName = wireName;
        this.byteLength = byteLength;
    }

    public String wireName() {
        return wireName;
    }

    public int byteLength() {
        return byteLength;
    }

    public void requireLength(int length) {
        if (length != byteLength) {
            throw new IllegalArgumentException("Git ID length does not match repository format " + wireName);
        }
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
