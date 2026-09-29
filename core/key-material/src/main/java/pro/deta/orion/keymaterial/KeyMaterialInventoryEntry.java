package pro.deta.orion.keymaterial;

import java.util.List;

/** Public metadata for one physical key-material entry. */
public record KeyMaterialInventoryEntry(
        String alias,
        String purpose,
        String algorithm,
        long version,
        String scope,
        String publicKeyPem,
        String publicKeySha256Fingerprint,
        List<CertificateDetails> certificates) {
    public KeyMaterialInventoryEntry {
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
