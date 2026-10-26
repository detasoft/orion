package pro.deta.orion.keymaterial;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public record TlsMaterialConfiguration(
        KeyMaterialDescriptor identity,
        Optional<KeyMaterialDescriptor> serverIssuerTrustAnchor,
        List<KeyMaterialDescriptor> clientTrustAnchors,
        TlsClientAuthentication clientAuthentication) {
    public TlsMaterialConfiguration {
        if (identity == null || !identity.purposes().contains(KeyMaterialPurpose.TLS_IDENTITY)
                || identity.purposes().contains(KeyMaterialPurpose.TRUST_ANCHOR)) {
            throw new IllegalArgumentException("TLS identity must use TLS_IDENTITY purpose");
        }
        if (serverIssuerTrustAnchor == null) {
            throw new IllegalArgumentException("TLS server issuer optional must not be null");
        }
        if (serverIssuerTrustAnchor.isPresent()
                && !serverIssuerTrustAnchor.orElseThrow().purposes().contains(KeyMaterialPurpose.TRUST_ANCHOR)) {
            throw new IllegalArgumentException("TLS server issuer must have TRUST_ANCHOR purpose");
        }
        if (clientTrustAnchors == null) {
            throw new IllegalArgumentException("TLS client trust anchors must not be null");
        }
        if (clientAuthentication == null) {
            throw new IllegalArgumentException("TLS client authentication must not be null");
        }
        List<KeyMaterialDescriptor> roots = new ArrayList<>();
        for (KeyMaterialDescriptor root : clientTrustAnchors) {
            if (root == null) {
                throw new IllegalArgumentException("TLS client trust anchor must not be null");
            }
            if (!root.purposes().contains(KeyMaterialPurpose.TRUST_ANCHOR)) {
                throw new IllegalArgumentException("TLS client trust anchors must have TRUST_ANCHOR purpose");
            }
            if (roots.contains(root)) {
                throw new IllegalArgumentException("TLS client trust anchors must not contain duplicates");
            }
            roots.add(root);
        }
        clientTrustAnchors = List.copyOf(roots);
        if (clientAuthentication != TlsClientAuthentication.DISABLED && clientTrustAnchors.isEmpty()) {
            throw new IllegalArgumentException("TLS client authentication requires client trust anchors");
        }
    }
}
