package pro.deta.orion.keymaterial;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.List;

public final class ServerIdentityMaterial implements ServerIdentityCapability, AutoCloseable {
    private final KeyMaterialService owner;
    private final ServerIdentityCapability capability;

    private ServerIdentityMaterial(
            KeyMaterialService owner,
            ServerIdentityCapability capability) {
        this.owner = owner;
        this.capability = capability;
    }

    public static ServerIdentityMaterial open(
            KeyMaterialContentStore store,
            KeyMaterialOptions options,
            SigningMaterialSet signingMaterial,
            int activeKeySize) throws IOException, GeneralSecurityException {
        requireRsa(signingMaterial);
        KeyMaterialService service = KeyMaterialService.open(store, options);
        try {
            if (!service.hasDurableSnapshot()) {
                if (!signingMaterial.verification().isEmpty()) {
                    throw new GeneralSecurityException(
                            "A new material store cannot contain retained server identities");
                }
                service.generateKeyIfMissing(signingMaterial.active(), activeKeySize);
                service.save();
            }
            List<KeyMaterialDescriptor> descriptors = signingMaterial.verificationIncludingActive();
            KeyMaterialCapabilities capabilities = KeyMaterialCapabilities.open(service, descriptors);
            return new ServerIdentityMaterial(service, capabilities.serverIdentity(signingMaterial));
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            service.close();
            throw failure;
        }
    }

    @Override
    public String activeKeyId() {
        return capability.activeKeyId();
    }

    @Override
    public byte[] sign(byte[] payload) throws GeneralSecurityException {
        return capability.sign(payload);
    }

    @Override
    public boolean hasVerificationKey(String keyId) {
        return capability.hasVerificationKey(keyId);
    }

    @Override
    public boolean verify(
            String keyId,
            byte[] payload,
            byte[] signature) throws GeneralSecurityException {
        return capability.verify(keyId, payload, signature);
    }

    @Override
    public List<PublicKey> publicKeys() throws GeneralSecurityException {
        return capability.publicKeys();
    }

    @Override
    public List<PublicKey> retainedPublicKeys() throws GeneralSecurityException {
        return capability.retainedPublicKeys();
    }

    @Override
    public void close() {
        owner.close();
    }

    private static void requireRsa(SigningMaterialSet signingMaterial) {
        if (signingMaterial == null) {
            throw new IllegalArgumentException("Server signing material must not be null");
        }
        if (signingMaterial.active().algorithm() != KeyMaterialAlgorithm.RSA) {
            throw new IllegalArgumentException("JWT server identity material must use RSA");
        }
    }
}
