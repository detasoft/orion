package pro.deta.orion.schema.orion;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record OrionHttpsConfiguration(
        boolean enabled,
        String address,
        int port,
        URI publicUrl,
        Optional<OrionMaterialReference> identity,
        Optional<OrionMaterialReference> serverIssuerTrustAnchor,
        ClientAuthentication clientAuthentication,
        List<OrionMaterialReference> clientTrustAnchors,
        Optional<OrionAcmeConfiguration> acme) {
    public OrionHttpsConfiguration {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("HTTPS port must be between 1 and 65535");
        }
        identity = Objects.requireNonNullElseGet(identity, Optional::empty);
        serverIssuerTrustAnchor = Objects.requireNonNullElseGet(
                serverIssuerTrustAnchor,
                Optional::empty);
        clientAuthentication = Objects.requireNonNullElse(
                clientAuthentication,
                ClientAuthentication.DISABLED);
        clientTrustAnchors = List.copyOf(Objects.requireNonNullElse(clientTrustAnchors, List.of()));
        acme = Objects.requireNonNullElseGet(acme, Optional::empty);
        if (enabled && (address == null || address.isBlank())) {
            throw new IllegalArgumentException("Enabled HTTPS requires an address");
        }
        if (enabled && identity.isEmpty()) {
            throw new IllegalArgumentException("Enabled HTTPS requires identity material");
        }
        if (clientAuthentication != ClientAuthentication.DISABLED && clientTrustAnchors.isEmpty()) {
            throw new IllegalArgumentException("Enabled client authentication requires trust anchors");
        }
        if (new HashSet<>(clientTrustAnchors).size() != clientTrustAnchors.size()) {
            throw new IllegalArgumentException("Duplicate client trust anchor");
        }
    }

    public enum ClientAuthentication {
        DISABLED,
        WANT,
        REQUIRED
    }
}
