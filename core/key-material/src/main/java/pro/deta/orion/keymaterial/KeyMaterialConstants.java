package pro.deta.orion.keymaterial;

import java.time.Duration;

public final class KeyMaterialConstants {
    public static final String DEFAULT_KEY_STORE_TYPE = "PKCS12";
    public static final String SHA_256_DIGEST = "SHA-256";
    public static final String SHA_256_VERSION_PREFIX = "sha256:";
    public static final String RSA_ALGORITHM = "RSA";
    public static final int RSA_KEY_SIZE_BITS = 2048;
    public static final String EC_ALGORITHM = "EC";
    public static final int EC_KEY_SIZE_BITS = 256;
    public static final String ECDSA_ALGORITHM_FRAGMENT = "ECDSA";
    public static final String ED25519_ALGORITHM = "ED25519";
    public static final String EDDSA_ALGORITHM_FRAGMENT = "EDDSA";
    public static final String SHA256_WITH_RSA_SIGNATURE = "SHA256withRSA";
    public static final String SHA256_WITH_ECDSA_SIGNATURE = "SHA256withECDSA";
    public static final String STORAGE_CERTIFICATE_ORGANIZATION = "Orion Storage";
    public static final Duration DEFAULT_STORAGE_CERTIFICATE_VALIDITY = Duration.ofDays(3650);
    public static final Duration STORAGE_CERTIFICATE_NOT_BEFORE_SKEW = Duration.ofMinutes(1);
    public static final int STORAGE_CERTIFICATE_SERIAL_BITS = 159;

    private KeyMaterialConstants() {
    }
}
