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

    static KeyMaterialAlgorithm fromKeyAlgorithm(String actual) {
        for (KeyMaterialAlgorithm algorithm : values()) {
            if (algorithm.acceptsKeyAlgorithm(actual)) return algorithm;
        }
        throw new IllegalArgumentException("Unsupported key material algorithm: " + actual);
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
