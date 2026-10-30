package pro.deta.orion.transport.http;

import pro.deta.orion.keymaterial.KeyMaterialDescriptor;

import java.util.List;
import java.util.Objects;

/** Public HTTP metadata for one material version. */
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
