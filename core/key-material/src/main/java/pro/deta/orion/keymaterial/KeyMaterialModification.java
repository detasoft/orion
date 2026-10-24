package pro.deta.orion.keymaterial;

import pro.deta.orion.util.Modification;

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

/** Collects append-only additions for one KMS version; deletion adds a new, marked version of an entry. */
public final class KeyMaterialModification implements Modification {
    private final KeyMaterialService owner;
    private final long version;
    private final List<Addition> additions = new ArrayList<>();
    private boolean finished;

    KeyMaterialModification(KeyMaterialService owner, long version) {
        this.owner = owner;
        this.version = version;
    }

    public long version() {
        return version;
    }

    public synchronized void generateKey(KeyMaterialDescriptor descriptor, int keySize)
            throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
        Addition existing = find(descriptor.alias());
        if (existing != null) {
            requireAvailable(existing);
            owner.validatePrepared(existing.entry(), descriptor);
            return;
        }
        add(descriptor.alias(), owner.prepareKey(descriptor, keySize), false);
    }

    public synchronized void importKey(KeyMaterialDescriptor descriptor, KeyPair keyPair)
            throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
        requireAbsent(descriptor.alias());
        add(descriptor.alias(), owner.preparePrivateKey(descriptor, keyPair), false);
    }

    public synchronized void importKey(KeyMaterialDescriptor descriptor, char[] privateKeyPem)
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
            importKey(descriptor, OrionKeyMaterial.readRsaPrivateKey(privateKeyPem));
        } finally {
            if (privateKeyPem != null) Arrays.fill(privateKeyPem, '\0');
        }
    }

    public synchronized void importSecretKey(KeyMaterialDescriptor descriptor, SecretKey key)
            throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
        requireAbsent(descriptor.alias());
        add(descriptor.alias(), owner.prepareSecretKey(descriptor, key), false);
    }

    public synchronized void setTrustedCertificate(TrustedCertificateDescriptor descriptor,
            X509Certificate certificate) throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
        Addition existing = find(descriptor.alias());
        if (existing != null) {
            requireAvailable(existing);
            owner.validatePrepared(existing.entry(), descriptor);
        }
        add(descriptor.alias(), owner.prepareTrustedCertificate(descriptor, certificate), false);
    }

    public synchronized void installCertificateChain(KeyMaterialDescriptor identity,
            List<? extends Certificate> chain, Optional<TrustedCertificateDescriptor> issuer)
            throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(identity);
        if (identity.purpose() != KeyMaterialPurpose.TLS_IDENTITY
                && identity.purpose() != KeyMaterialPurpose.CERTIFICATE_AUTHORITY) {
            throw new IllegalArgumentException("Certificate chains require TLS identity or CA material");
        }
        if (issuer == null) throw new IllegalArgumentException("Issuer optional must not be null");
        Addition existing = requireEntry(identity.alias());
        owner.validatePrepared(existing.entry(), identity);
        KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) existing.entry();
        Optional<X509Certificate> root = Optional.empty();
        if (issuer.isPresent()) {
            TrustedCertificateDescriptor descriptor = issuer.orElseThrow();
            requireDescriptor(descriptor);
            Addition trusted = requireEntry(descriptor.alias());
            owner.validatePrepared(trusted.entry(), descriptor);
            root = Optional.of((X509Certificate) ((KeyStore.TrustedCertificateEntry) trusted.entry())
                    .getTrustedCertificate());
        }
        List<X509Certificate> certificates = KeyMaterialCapabilities.requireX509Chain(chain);
        KeyPair keyPair = new KeyPair(entry.getCertificate().getPublicKey(), entry.getPrivateKey());
        KeyMaterialCapabilities.validateCertificateChain(certificates, keyPair.getPublic(), root);
        add(identity.alias(), owner.prepareCertificateChain(identity, keyPair, certificates), false);
    }

    public synchronized void delete(KeyMaterialDescriptor descriptor) throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
        Addition existing = requireEntry(descriptor.alias());
        owner.validatePrepared(existing.entry(), descriptor);
        add(existing.alias(), existing.entry(), true);
    }

    public synchronized void delete(TrustedCertificateDescriptor descriptor) throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
        Addition existing = requireEntry(descriptor.alias());
        owner.validatePrepared(existing.entry(), descriptor);
        add(existing.alias(), existing.entry(), true);
    }

    @Override
    public synchronized void apply() throws IOException {
        try {
            requireOpen();
            owner.apply(additions, version);
        } finally {
            discard();
        }
    }

    @Override
    public synchronized void discard() {
        additions.clear();
        finished = true;
    }

    private Addition find(String alias) throws GeneralSecurityException {
        for (int index = additions.size() - 1; index >= 0; index--) {
            Addition addition = additions.get(index);
            if (addition.alias().equalsIgnoreCase(alias)) return addition;
        }
        return owner.readAddition(alias);
    }

    private Addition requireEntry(String alias) throws GeneralSecurityException {
        Addition addition = find(alias);
        if (addition == null) throw new GeneralSecurityException("Key material alias not found: " + alias);
        requireAvailable(addition);
        return addition;
    }

    private void requireAbsent(String alias) throws GeneralSecurityException {
        if (find(alias) != null) throw new IllegalArgumentException("Key material name already exists");
    }

    private void requireAvailable(Addition addition) throws GeneralSecurityException {
        if (addition.deleted()) throw new GeneralSecurityException("Key material is deleted: " + addition.alias());
    }

    private void add(String alias, KeyStore.Entry entry, boolean deleted) throws GeneralSecurityException {
        Addition previous = find(alias);
        long next = previous == null ? 1 : Math.incrementExact(previous.version());
        additions.add(new Addition(alias, entry, next, deleted));
    }

    private void requireOpen() {
        if (finished) throw new IllegalStateException("Key material modification is already finished");
        owner.requireOpen();
    }

    private void requireDescriptor(KeyMaterialDescriptor descriptor) {
        if (descriptor == null) throw new IllegalArgumentException("Key material descriptor must not be null");
    }

    private void requireDescriptor(TrustedCertificateDescriptor descriptor) {
        if (descriptor == null) throw new IllegalArgumentException("Trusted certificate descriptor must not be null");
    }

    record Addition(String alias, KeyStore.Entry entry, long version, boolean deleted) {}

    @FunctionalInterface
    public interface Operation {
        void accept(KeyMaterialModification modification) throws IOException, GeneralSecurityException;
    }
}
