package pro.deta.orion.keymaterial;

import java.security.KeyPair;

public record AcmeKeyMaterial(KeyPair accountKeyPair, KeyPair domainKeyPair) {
    public AcmeKeyMaterial {
        requireComplete(accountKeyPair, "ACME account");
        requireComplete(domainKeyPair, "ACME domain");
    }

    private static void requireComplete(KeyPair keyPair, String label) {
        if (keyPair == null || keyPair.getPublic() == null || keyPair.getPrivate() == null) {
            throw new IllegalArgumentException(label + " key pair must be complete");
        }
    }
}
