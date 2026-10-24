package pro.deta.orion.keymaterial;

import java.util.List;
import java.util.Set;

/** Public metadata for one physical key-material entry. */
public record KeyMaterialInventoryEntry(
        String alias,
        Set<KeyMaterialPurpose> purposes,
        String algorithm,
        long version,
        String publicKeyPem,
        String publicKeySha256Fingerprint,
        List<CertificateDetails> certificates) {
    public KeyMaterialInventoryEntry {
        purposes = Set.copyOf(purposes);
        certificates = List.copyOf(certificates);
    }

    public record CertificateDetails(
            String subject,
            List<String> dnsNames,
            String issuer,
            String serialNumber,
            String validFrom,
            String validUntil,
            String sha256Fingerprint) {
        public CertificateDetails {
            dnsNames = List.copyOf(dnsNames);
        }
    }
}
