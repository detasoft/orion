package pro.deta.orion.keymaterial;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.List;

public interface ServerIdentityCapability {
    String activeKeyId();

    byte[] sign(byte[] payload) throws GeneralSecurityException;

    boolean hasVerificationKey(String keyId);

    boolean verify(String keyId, byte[] payload, byte[] signature) throws GeneralSecurityException;

    List<PublicKey> publicKeys() throws GeneralSecurityException;

    List<PublicKey> retainedPublicKeys() throws GeneralSecurityException;

    static ServerIdentityCapability unavailable() {
        return new ServerIdentityCapability() {
            @Override
            public String activeKeyId() {
                throw new IllegalStateException("Server identity is not available");
            }

            @Override
            public byte[] sign(byte[] payload) throws GeneralSecurityException {
                throw new GeneralSecurityException("Server identity is not available");
            }

            @Override
            public boolean hasVerificationKey(String keyId) {
                return false;
            }

            @Override
            public boolean verify(String keyId, byte[] payload, byte[] signature) {
                return false;
            }

            @Override
            public List<PublicKey> publicKeys() {
                return List.of();
            }

            @Override
            public List<PublicKey> retainedPublicKeys() {
                return List.of();
            }
        };
    }
}
