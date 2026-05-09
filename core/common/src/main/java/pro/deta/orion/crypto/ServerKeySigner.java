package pro.deta.orion.crypto;

import java.security.PublicKey;

public interface ServerKeySigner {
    ServerKeySigner DEFAULT = () -> {
        throw new IllegalStateException("Server signing key is not available");
    };

    SigningKey rsaSha256SigningKey();

    interface SigningKey {
        PublicKey publicKey();

        byte[] sign(byte[] data);
    }
}
