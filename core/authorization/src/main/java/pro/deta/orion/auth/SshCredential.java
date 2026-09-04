package pro.deta.orion.auth;

import java.util.Objects;

public record SshCredential(String algorithm, String fingerprint) {
    public SshCredential {
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(fingerprint, "fingerprint");
    }
}
