package pro.deta.orion.keymaterial;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class KeyMaterialVersions {
    private KeyMaterialVersions() {
    }

    static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance(KeyMaterialConstants.SHA_256_DIGEST);
            return KeyMaterialConstants.SHA_256_VERSION_PREFIX + HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(KeyMaterialConstants.SHA_256_DIGEST + " message digest is not available", e);
        }
    }
}
