package pro.deta.orion.resource.address;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class ResourceContentVersions {
    private ResourceContentVersions() {
    }

    static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ResourceAddressConstants.SHA_256_DIGEST);
            return ResourceAddressConstants.SHA_256_VERSION_PREFIX + HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    ResourceAddressConstants.SHA_256_DIGEST + " message digest is not available",
                    e);
        }
    }
}
