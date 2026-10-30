package pro.deta.orion.keymaterial;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public record TlsMaterialConfiguration(
        KeyMaterialId identity,
        Optional<KeyMaterialId> serverIssuerTrustAnchor,
        List<KeyMaterialId> clientTrustAnchors,
        TlsClientAuthentication clientAuthentication) {
    public TlsMaterialConfiguration {
        if (identity == null) throw new IllegalArgumentException("TLS identity must not be null");
        if (serverIssuerTrustAnchor == null) {
            throw new IllegalArgumentException("TLS server issuer optional must not be null");
        }
        if (clientTrustAnchors == null) {
            throw new IllegalArgumentException("TLS client trust anchors must not be null");
        }
        if (clientAuthentication == null) {
            throw new IllegalArgumentException("TLS client authentication must not be null");
        }
        List<KeyMaterialId> roots = new ArrayList<>();
        for (KeyMaterialId root : clientTrustAnchors) {
            if (root == null) {
                throw new IllegalArgumentException("TLS client trust anchor must not be null");
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
