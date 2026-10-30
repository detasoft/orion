package pro.deta.orion.keymaterial;

import pro.deta.orion.util.Modification;
import pro.deta.orion.keymaterial.KeyMaterialRepository.Entry;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Collects append-only additions against a captured source revision; deletion appends a marked entry. */
public final class KeyMaterialModification implements Modification {
    private final KeyMaterialService owner;
    private final String expectedRevision;
    private final List<Entry> additions = new ArrayList<>();
    private boolean finished;

    KeyMaterialModification(KeyMaterialService owner, String expectedRevision) {
        this.owner = owner;
        this.expectedRevision = expectedRevision;
    }

    public String version() {
        return expectedRevision;
    }

    public synchronized KeyMaterialId generateKey(KeyMaterialDescriptor descriptor, int keySize)
            throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
        Entry existing = find(descriptor.alias());
        if (existing != null) {
            requireAvailable(existing);
            Entry selected = requireEntry(descriptor.id());
            owner.validatePrepared(selected.entry(), descriptor);
            return descriptor.id();
        }
        requireFirstVersion(descriptor);
        return add(descriptor.alias(), owner.prepareKey(descriptor, keySize), false);
    }

    public synchronized KeyMaterialId importKey(KeyMaterialDescriptor descriptor, KeyPair keyPair)
            throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
        Entry existing = validateTarget(descriptor);
        KeyStore.PrivateKeyEntry prepared = owner.preparePrivateKey(descriptor, keyPair);
        if (existing != null) {
            prepared = new KeyStore.PrivateKeyEntry(prepared.getPrivateKey(), prepared.getCertificateChain(),
                    existing.entry().getAttributes());
        }
        return add(descriptor.alias(), prepared, false);
    }

    public synchronized KeyMaterialId importKey(KeyMaterialDescriptor descriptor, char[] privateKeyPem)
            throws GeneralSecurityException {
        try {
            requireOpen();
            requireDescriptor(descriptor);
            if (privateKeyPem == null || privateKeyPem.length == 0) {
                throw new IllegalArgumentException("Private key input must not be empty");
            }
            if (descriptor.algorithm() != KeyMaterialAlgorithm.RSA) {
                throw new IllegalArgumentException("PEM and JWK import requires an RSA descriptor");
            }
            return importKey(descriptor, OrionKeyMaterial.readRsaPrivateKey(privateKeyPem));
        } finally {
            if (privateKeyPem != null) Arrays.fill(privateKeyPem, '\0');
        }
    }

    public synchronized KeyMaterialId importSecretKey(KeyMaterialDescriptor descriptor, SecretKey key)
            throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
        Entry existing = validateTarget(descriptor);
        KeyStore.SecretKeyEntry prepared = owner.prepareSecretKey(descriptor, key);
        if (existing != null) {
            prepared = new KeyStore.SecretKeyEntry(prepared.getSecretKey(), existing.entry().getAttributes());
        }
        return add(descriptor.alias(), prepared, false);
    }

    public synchronized KeyMaterialId setTrustedCertificate(KeyMaterialDescriptor descriptor,
            X509Certificate certificate) throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
        Entry existing = validateTarget(descriptor);
        KeyStore.TrustedCertificateEntry prepared = owner.prepareTrustedCertificate(descriptor, certificate);
        if (existing != null) {
            prepared = new KeyStore.TrustedCertificateEntry(certificate, existing.entry().getAttributes());
        }
        return add(descriptor.alias(), prepared, false);
    }

    public synchronized KeyMaterialId installCertificateChain(KeyMaterialDescriptor identity,
            List<? extends Certificate> chain, Optional<KeyMaterialDescriptor> issuer)
            throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(identity);
        if (identity.purposes().contains(KeyMaterialPurpose.TRUST_ANCHOR)
                || (!identity.purposes().contains(KeyMaterialPurpose.TLS_IDENTITY)
                        && !identity.purposes().contains(KeyMaterialPurpose.CERTIFICATE_AUTHORITY))) {
            throw new IllegalArgumentException("Certificate chains require TLS identity or CA material");
        }
        if (issuer == null) throw new IllegalArgumentException("Issuer optional must not be null");
        Entry existing = requireEntry(identity.id());
        owner.validatePrepared(existing.entry(), identity);
        KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) existing.entry();
        Optional<X509Certificate> root = Optional.empty();
        if (issuer.isPresent()) {
            KeyMaterialDescriptor descriptor = issuer.orElseThrow();
            requireDescriptor(descriptor);
            if (!descriptor.purposes().contains(KeyMaterialPurpose.TRUST_ANCHOR)) {
                throw new IllegalArgumentException("Issuer must have TRUST_ANCHOR purpose");
            }
            Entry trusted = requireEntry(descriptor.id());
            owner.validatePrepared(trusted.entry(), descriptor);
            root = Optional.of((X509Certificate) ((KeyStore.TrustedCertificateEntry) trusted.entry())
                    .getTrustedCertificate());
        }
        List<X509Certificate> certificates = CertificateIdentity.requireX509Chain(chain);
        KeyPair keyPair = new KeyPair(entry.getCertificate().getPublicKey(), entry.getPrivateKey());
        CertificateIdentity.validateCertificateChain(certificates, keyPair.getPublic(), root);
        KeyStore.PrivateKeyEntry prepared = owner.prepareCertificateChain(identity, keyPair, certificates);
        return add(identity.alias(), new KeyStore.PrivateKeyEntry(prepared.getPrivateKey(),
                prepared.getCertificateChain(), entry.getAttributes()), false);
    }

    public synchronized KeyMaterialId delete(KeyMaterialDescriptor descriptor) throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
        Entry existing = requireEntry(descriptor.id());
        owner.validatePrepared(existing.entry(), descriptor);
        return add(existing.alias(), existing.entry(), true);
    }

    @Override
    public synchronized void apply() throws IOException {
        try {
            requireOpen();
            owner.apply(additions, expectedRevision);
        } finally {
            discard();
        }
    }

    @Override
    public synchronized void discard() {
        additions.clear();
        finished = true;
    }

    private Entry find(String alias) throws GeneralSecurityException {
        for (int index = additions.size() - 1; index >= 0; index--) {
            Entry addition = additions.get(index);
            if (addition.alias().equalsIgnoreCase(alias)) return addition;
        }
        return owner.readEntry(alias);
    }

    private Entry requireEntry(KeyMaterialId id) throws GeneralSecurityException {
        Entry latest = find(id.alias());
        if (latest != null) requireAvailable(latest);
        for (int index = additions.size() - 1; index >= 0; index--) {
            Entry entry = additions.get(index);
            if (entry.alias().equalsIgnoreCase(id.alias()) && entry.version() == id.version()) return entry;
        }
        Entry entry = owner.readEntry(id);
        if (entry == null) throw new GeneralSecurityException("Key material version not found: " + id);
        requireAvailable(entry);
        return entry;
    }

    private Entry validateTarget(KeyMaterialDescriptor descriptor) throws GeneralSecurityException {
        if (find(descriptor.alias()) == null) {
            requireFirstVersion(descriptor);
            return null;
        }
        Entry existing = requireEntry(descriptor.id());
        owner.validatePrepared(existing.entry(), descriptor);
        return existing;
    }

    private void requireFirstVersion(KeyMaterialDescriptor descriptor) {
        if (descriptor.version() != 1) {
            throw new IllegalArgumentException("New key material must start at version 1");
        }
    }

    private void requireAvailable(Entry addition) throws GeneralSecurityException {
        if (addition.deleted()) throw new GeneralSecurityException("Key material is deleted: " + addition.alias());
    }

    private KeyMaterialId add(String alias, KeyStore.Entry entry, boolean deleted)
            throws GeneralSecurityException {
        Entry previous = find(alias);
        long next = previous == null ? 1 : Math.incrementExact(previous.version());
        additions.add(new Entry(alias, entry, next, deleted));
        return new KeyMaterialId(alias, next);
    }

    private void requireOpen() {
        if (finished) throw new IllegalStateException("Key material modification is already finished");
        owner.requireOpen();
    }

    private void requireDescriptor(KeyMaterialDescriptor descriptor) {
        if (descriptor == null) throw new IllegalArgumentException("Key material descriptor must not be null");
    }

    @FunctionalInterface
    public interface Operation {
        void accept(KeyMaterialModification modification) throws IOException, GeneralSecurityException;
    }
}
