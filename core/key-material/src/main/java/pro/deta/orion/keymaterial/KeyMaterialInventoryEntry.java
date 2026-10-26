package pro.deta.orion.keymaterial;

import java.util.List;
import java.util.Objects;

/** Public metadata for one physical key-material entry. */
public record KeyMaterialInventoryEntry(
        KeyMaterialDescriptor descriptor,
        String publicKeyPem,
        String publicKeySha256Fingerprint,
        List<CertificateDetails> certificates) {
    public KeyMaterialInventoryEntry {
        Objects.requireNonNull(descriptor, "descriptor");
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
