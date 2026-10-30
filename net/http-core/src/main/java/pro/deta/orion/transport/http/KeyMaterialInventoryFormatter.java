package pro.deta.orion.transport.http;

import pro.deta.orion.keymaterial.KeyMaterialEntry;
import pro.deta.orion.keymaterial.KeyMaterialId;
import pro.deta.orion.keymaterial.OrionKeyMaterial;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;

/** Formats validated material as public HTTP metadata, without exposing private or symmetric keys. */
public final class KeyMaterialInventoryFormatter {
    private KeyMaterialInventoryFormatter() {}

    public static List<KeyMaterialInventoryEntry> format(OrionKeyMaterial material)
            throws GeneralSecurityException {
        List<KeyMaterialInventoryEntry> result = new ArrayList<>();
        for (KeyMaterialId id : material.inventory()) {
            KeyMaterialEntry entry = material.read(id);
            Certificate[] chain = switch (entry.content()) {
                case KeyStore.PrivateKeyEntry key -> key.getCertificateChain();
                case KeyStore.TrustedCertificateEntry trusted ->
                        new Certificate[]{trusted.getTrustedCertificate()};
                case KeyStore.SecretKeyEntry ignored -> new Certificate[0];
                default -> throw new GeneralSecurityException("Unknown key material entry type: " + id);
            };
            String pem = chain.length == 0 ? null : publicKeyPem(chain[0].getPublicKey());
            String fingerprint = chain.length == 0 ? null : HexFormat.of().withDelimiter(":").formatHex(
                    MessageDigest.getInstance("SHA-256").digest(chain[0].getPublicKey().getEncoded()));
            List<KeyMaterialInventoryEntry.CertificateDetails> certificates = new ArrayList<>();
            boolean storageCertificate = entry.content() instanceof KeyStore.PrivateKeyEntry
                    && entry.issuedCertificateChain().isEmpty();
            if (!storageCertificate) {
                for (Certificate certificate : chain) {
                    if (!(certificate instanceof X509Certificate x509)) {
                        throw new GeneralSecurityException("Non-X509 certificate in key material: " + id);
                    }
                    certificates.add(certificateDetails(x509));
                }
            }
            result.add(new KeyMaterialInventoryEntry(new KeyMaterialInventoryEntry.Metadata(
                    entry.id().alias(), entry.purposes(), entry.algorithm(), entry.id().version()),
                    pem, fingerprint, certificates));
        }
        return List.copyOf(result);
    }

    private static String publicKeyPem(PublicKey key) {
        String encoded = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(key.getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----";
    }

    private static KeyMaterialInventoryEntry.CertificateDetails certificateDetails(X509Certificate certificate)
            throws GeneralSecurityException {
        byte[] fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        List<String> dnsNames = new ArrayList<>();
        Collection<List<?>> alternatives = certificate.getSubjectAlternativeNames();
        if (alternatives != null) {
            for (List<?> alternative : alternatives) {
                if (Integer.valueOf(2).equals(alternative.get(0))
                        && alternative.get(1) instanceof String name) {
                    dnsNames.add(name);
                }
            }
        }
        return new KeyMaterialInventoryEntry.CertificateDetails(
                certificate.getSubjectX500Principal().getName(),
                dnsNames,
                certificate.getIssuerX500Principal().getName(),
                certificate.getSerialNumber().toString(16),
                certificate.getNotBefore().toInstant().toString(),
                certificate.getNotAfter().toInstant().toString(),
                HexFormat.of().withDelimiter(":").formatHex(fingerprint));
    }

}
