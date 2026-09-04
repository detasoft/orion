package pro.deta.orion.auth;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record SshConnectionCredentials(
        Optional<String> authenticatedKeyFingerprint,
        List<String> candidatePublicKeys) {
    public SshConnectionCredentials {
        Objects.requireNonNull(authenticatedKeyFingerprint, "authenticatedKeyFingerprint");
        Objects.requireNonNull(candidatePublicKeys, "candidatePublicKeys");
        authenticatedKeyFingerprint = authenticatedKeyFingerprint.map(String::trim).filter(value -> !value.isEmpty());
        candidatePublicKeys = List.copyOf(candidatePublicKeys);
    }

    public SshConnectionCredentials(String authenticatedKeyFingerprint, List<String> candidatePublicKeys) {
        this(Optional.ofNullable(authenticatedKeyFingerprint), candidatePublicKeys);
    }

    public static SshConnectionCredentials empty() {
        return new SshConnectionCredentials(Optional.empty(), List.of());
    }

    @Override
    public String toString() {
        return "SshConnectionCredentials[authenticatedKeyFingerprint=" + authenticatedKeyFingerprint
                + ", candidateCount=" + candidatePublicKeys.size() + "]";
    }
}
