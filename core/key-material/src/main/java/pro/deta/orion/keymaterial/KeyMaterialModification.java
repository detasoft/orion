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
import java.util.Set;

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

    public synchronized KeyMaterialId generateKey(String alias, Set<KeyMaterialPurpose> purposes,
            KeyMaterialAlgorithm algorithm, int keySize) throws GeneralSecurityException {
        requireOpen();
        KeyMaterialId id = new KeyMaterialId(alias, 1);
        if (find(id.alias()) != null) {
            Entry selected = requireEntry(id);
            owner.validatePrepared(id.alias(), selected.entry(), purposes, algorithm);
            return id;
        }
        return add(id.alias(), owner.prepareKey(id.alias(), purposes, algorithm, keySize), false);
    }

    public synchronized KeyMaterialId importKey(String alias, Set<KeyMaterialPurpose> purposes,
            KeyMaterialAlgorithm algorithm, KeyPair keyPair) throws GeneralSecurityException {
        requireOpen();
        alias = requireNewAlias(alias);
        return add(alias, owner.preparePrivateKey(alias, purposes, algorithm, keyPair), false);
    }

    public synchronized KeyMaterialId importKey(KeyMaterialId id, KeyPair keyPair)
            throws GeneralSecurityException {
        requireOpen();
        KeyMaterialEntry existing = owner.describe(requireEntry(id));
        KeyStore.PrivateKeyEntry prepared = owner.preparePrivateKey(id.alias(), existing.purposes(),
                existing.algorithm(), keyPair);
        return add(id.alias(), new KeyStore.PrivateKeyEntry(prepared.getPrivateKey(),
                prepared.getCertificateChain(), existing.content().getAttributes()), false);
    }

    public synchronized KeyMaterialId importKey(String alias, Set<KeyMaterialPurpose> purposes,
            KeyMaterialAlgorithm algorithm, char[] privateKeyPem) throws GeneralSecurityException {
        try {
            requireOpen();
            if (privateKeyPem == null || privateKeyPem.length == 0) {
                throw new IllegalArgumentException("Private key input must not be empty");
            }
            if (algorithm != KeyMaterialAlgorithm.RSA) {
                throw new IllegalArgumentException("PEM and JWK import requires RSA material");
            }
            return importKey(alias, purposes, algorithm, OrionKeyMaterial.readRsaPrivateKey(privateKeyPem));
        } finally {
            if (privateKeyPem != null) Arrays.fill(privateKeyPem, '\0');
        }
    }

    public synchronized KeyMaterialId importSecretKey(String alias, Set<KeyMaterialPurpose> purposes,
            KeyMaterialAlgorithm algorithm, SecretKey key) throws GeneralSecurityException {
        requireOpen();
        alias = requireNewAlias(alias);
        return add(alias, owner.prepareSecretKey(alias, purposes, algorithm, key), false);
    }

    public synchronized KeyMaterialId importSecretKey(KeyMaterialId id, SecretKey key)
            throws GeneralSecurityException {
        requireOpen();
        KeyMaterialEntry existing = owner.describe(requireEntry(id));
        KeyStore.SecretKeyEntry prepared = owner.prepareSecretKey(id.alias(), existing.purposes(),
                existing.algorithm(), key);
        return add(id.alias(), new KeyStore.SecretKeyEntry(prepared.getSecretKey(),
                existing.content().getAttributes()), false);
    }

    public synchronized KeyMaterialId setTrustedCertificate(String alias, Set<KeyMaterialPurpose> purposes,
            KeyMaterialAlgorithm algorithm, X509Certificate certificate) throws GeneralSecurityException {
        requireOpen();
        alias = requireNewAlias(alias);
        return add(alias, owner.prepareTrustedCertificate(alias, purposes, algorithm, certificate), false);
    }

    public synchronized KeyMaterialId setTrustedCertificate(KeyMaterialId id, X509Certificate certificate)
            throws GeneralSecurityException {
        requireOpen();
        KeyMaterialEntry existing = owner.describe(requireEntry(id));
        owner.prepareTrustedCertificate(id.alias(), existing.purposes(), existing.algorithm(), certificate);
        return add(id.alias(), new KeyStore.TrustedCertificateEntry(certificate,
                existing.content().getAttributes()), false);
    }

    public synchronized KeyMaterialId installCertificateChain(KeyMaterialId identity,
            List<? extends Certificate> chain, Optional<KeyMaterialId> issuer) throws GeneralSecurityException {
        requireOpen();
        KeyMaterialEntry existing = owner.describe(requireEntry(identity));
        if (existing.purposes().contains(KeyMaterialPurpose.TRUST_ANCHOR)
                || (!existing.purposes().contains(KeyMaterialPurpose.TLS_IDENTITY)
                        && !existing.purposes().contains(KeyMaterialPurpose.CERTIFICATE_AUTHORITY))) {
            throw new IllegalArgumentException("Certificate chains require TLS identity or CA material");
        }
        if (issuer == null) throw new IllegalArgumentException("Issuer optional must not be null");
        Optional<X509Certificate> root = Optional.empty();
        if (issuer.isPresent()) {
            KeyMaterialEntry trusted = owner.describe(requireEntry(issuer.orElseThrow()));
            if (!trusted.purposes().contains(KeyMaterialPurpose.TRUST_ANCHOR)) {
                throw new IllegalArgumentException("Issuer must have TRUST_ANCHOR purpose");
            }
            root = Optional.of(trusted.trustedCertificate());
        }
        List<X509Certificate> certificates = CertificateIdentity.requireX509Chain(chain);
        KeyPair keyPair = existing.keyPair();
        CertificateIdentity.validateCertificateChain(certificates, keyPair.getPublic(), root);
        KeyStore.PrivateKeyEntry prepared = owner.prepareCertificateChain(identity.alias(), existing.purposes(),
                existing.algorithm(), keyPair, certificates);
        return add(identity.alias(), new KeyStore.PrivateKeyEntry(prepared.getPrivateKey(),
                prepared.getCertificateChain(), existing.content().getAttributes()), false);
    }

    public synchronized KeyMaterialId delete(KeyMaterialId id) throws GeneralSecurityException {
        requireOpen();
        Entry existing = requireEntry(id);
        owner.describe(existing);
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

    private String requireNewAlias(String alias) throws GeneralSecurityException {
        String normalized = new KeyMaterialId(alias, 1).alias();
        if (find(normalized) != null) throw new IllegalArgumentException("Key material name already exists");
        return normalized;
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

    @FunctionalInterface
    public interface Operation {
        void accept(KeyMaterialModification modification) throws IOException, GeneralSecurityException;
    }
}
