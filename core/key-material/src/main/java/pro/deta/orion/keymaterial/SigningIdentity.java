package pro.deta.orion.keymaterial;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Selected active signing key and the explicitly retained verification keys, bound to their material owner. */
public class SigningIdentity {
    private final KeyMaterialService owner;
    private final KeyMaterialCapabilities selected;
    private final SigningMaterialSet material;
    private final Map<String, KeyMaterialDescriptor> verification;

    protected SigningIdentity() {
        owner = null;
        selected = null;
        material = null;
        verification = Map.of();
    }

    SigningIdentity(KeyMaterialService owner, KeyMaterialCapabilities selected, SigningMaterialSet material) {
        this.owner = owner;
        this.selected = selected;
        this.material = material;
        Map<String, KeyMaterialDescriptor> keys = new LinkedHashMap<>();
        for (KeyMaterialDescriptor descriptor : material.verificationIncludingActive()) {
            keys.put(descriptor.alias(), descriptor);
        }
        verification = Map.copyOf(keys);
    }

    public static SigningIdentity unavailable() {
        return new SigningIdentity();
    }

    public String activeKeyId() {
        if (material == null) throw new IllegalStateException("Server identity is not available");
        return material.active().alias();
    }

    public byte[] sign(byte[] payload) throws GeneralSecurityException {
        if (selected == null) throw new GeneralSecurityException("Server identity is not available");
        return selected.signWith(material.active(), payload);
    }

    public boolean hasVerificationKey(String keyId) {
        return keyId != null && verification.containsKey(keyId);
    }

    public boolean verify(String keyId, byte[] payload, byte[] signature) throws GeneralSecurityException {
        KeyMaterialDescriptor descriptor = keyId == null ? null : verification.get(keyId);
        return descriptor != null && selected.verification(List.of(descriptor)).verify(payload, signature);
    }

    public List<PublicKey> publicKeys() throws GeneralSecurityException {
        if (owner == null) return List.of();
        return List.of(owner.getKeyPair(material.active().alias()).getPublic());
    }

    public List<PublicKey> retainedPublicKeys() throws GeneralSecurityException {
        if (owner == null) return List.of();
        List<PublicKey> keys = new ArrayList<>();
        for (KeyMaterialDescriptor descriptor : material.verification()) {
            keys.add(owner.getKeyPair(descriptor.alias()).getPublic());
        }
        return List.copyOf(keys);
    }
}
