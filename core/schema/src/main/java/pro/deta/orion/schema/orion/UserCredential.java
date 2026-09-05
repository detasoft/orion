package pro.deta.orion.schema.orion;

import java.util.Base64;
import java.util.List;
import java.util.Objects;

public final class UserCredential {
    private static final List<String> OPENSSH_ALGORITHMS = List.of(
            "ssh-ed25519",
            "ssh-rsa",
            "ecdsa-sha2-nistp256",
            "ecdsa-sha2-nistp384",
            "ecdsa-sha2-nistp521",
            "sk-ssh-ed25519@openssh.com",
            "sk-ecdsa-sha2-nistp256@openssh.com");

    private final Type type;
    private final String keyId;
    private final String value;

    private UserCredential(Type type, String keyId, String value) {
        this.type = Objects.requireNonNull(type, "credential type");
        this.keyId = requireOptionalKeyId(keyId);
        this.value = requireValue(value);
    }

    public static UserCredential passwordVerifier(Type type, String value) {
        Objects.requireNonNull(type, "credential type");
        if (type != Type.ARGON2 && type != Type.SHA1) {
            throw new IllegalArgumentException("credential type is not a password verifier: " + type);
        }
        return new UserCredential(type, null, value);
    }

    public static UserCredential publicKey(String value) {
        return publicKey(null, value);
    }

    public static UserCredential publicKey(String keyId, String value) {
        validateOpenSshPublicKey(value);
        return new UserCredential(Type.OPENSSH_PUBLIC_KEY, keyId, value);
    }

    public Type type() {
        return type;
    }

    public String keyId() {
        return keyId;
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof UserCredential credential)) {
            return false;
        }
        return type == credential.type
                && Objects.equals(keyId, credential.keyId)
                && value.equals(credential.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, keyId, value);
    }

    private static String requireOptionalKeyId(String keyId) {
        if (keyId != null && keyId.isBlank()) {
            throw new IllegalArgumentException("credential key id must not be blank");
        }
        return keyId;
    }

    private static String requireValue(String value) {
        Objects.requireNonNull(value, "credential value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("credential value must not be blank");
        }
        return value;
    }

    private static void validateOpenSshPublicKey(String value) {
        String canonical = requireValue(value);
        int separator = canonical.indexOf(' ');
        if (separator <= 0 || separator != canonical.lastIndexOf(' ') || separator == canonical.length() - 1) {
            throw invalidOpenSshPublicKey();
        }
        String algorithm = canonical.substring(0, separator);
        if (!OPENSSH_ALGORITHMS.contains(algorithm)) {
            throw invalidOpenSshPublicKey();
        }
        String payload = canonical.substring(separator + 1);
        try {
            byte[] decoded = Base64.getDecoder().decode(payload);
            if (decoded.length == 0 || !Base64.getEncoder().encodeToString(decoded).equals(payload)) {
                throw invalidOpenSshPublicKey();
            }
        } catch (IllegalArgumentException exception) {
            throw invalidOpenSshPublicKey();
        }
    }

    private static IllegalArgumentException invalidOpenSshPublicKey() {
        return new IllegalArgumentException("credential value must be a canonical OpenSSH public key");
    }

    public enum Type {
        ARGON2,
        SHA1,
        OPENSSH_PUBLIC_KEY
    }
}
