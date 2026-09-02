package pro.deta.orion.keymaterial;

import java.util.Set;

public enum KeyMaterialAlgorithm {
    RSA("RSA", "SHA256withRSA", "RSA"),
    EC("EC", "SHA256withECDSA", "EC"),
    ED25519("Ed25519", "Ed25519", "Ed25519", "EdDSA"),
    AES("AES", null, "AES");

    private final String keyAlgorithm;
    private final String signatureAlgorithm;
    private final Set<String> acceptedKeyAlgorithms;

    KeyMaterialAlgorithm(String keyAlgorithm, String signatureAlgorithm, String... acceptedKeyAlgorithms) {
        this.keyAlgorithm = keyAlgorithm;
        this.signatureAlgorithm = signatureAlgorithm;
        this.acceptedKeyAlgorithms = Set.of(acceptedKeyAlgorithms);
    }

    public String keyAlgorithm() {
        return keyAlgorithm;
    }

    boolean acceptsKeyAlgorithm(String actual) {
        for (String accepted : acceptedKeyAlgorithms) {
            if (accepted.equalsIgnoreCase(actual)) {
                return true;
            }
        }
        return false;
    }

    String requireSignatureAlgorithm() {
        if (signatureAlgorithm == null) {
            throw new IllegalArgumentException(name() + " does not support signatures");
        }
        return signatureAlgorithm;
    }
}
