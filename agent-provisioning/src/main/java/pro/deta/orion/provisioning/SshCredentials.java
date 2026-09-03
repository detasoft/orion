package pro.deta.orion.provisioning;

import java.security.KeyPair;

public record SshCredentials(KeyPair keyPair) {
    public SshCredentials {
        if (keyPair == null || keyPair.getPrivate() == null || keyPair.getPublic() == null) {
            throw new IllegalArgumentException("SSH credentials must include a public and private key");
        }
        keyPair = new KeyPair(keyPair.getPublic(), keyPair.getPrivate());
    }

    @Override
    public KeyPair keyPair() {
        return new KeyPair(keyPair.getPublic(), keyPair.getPrivate());
    }
}
