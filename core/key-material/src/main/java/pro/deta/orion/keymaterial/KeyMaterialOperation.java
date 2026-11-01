package pro.deta.orion.keymaterial;

import pro.deta.orion.keymaterial.KeyMaterialRepository.Entry;
import pro.deta.orion.storage.BlobStorage.BlobStorageOperations;
import pro.deta.orion.util.VcRevision;
import pro.deta.orion.util.VcChangeDescription;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.UncheckedIOException;
import pro.deta.orion.util.VcResource;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Reads and prepares append-only changes against one opened source snapshot; deletion appends a marked entry. */
public final class KeyMaterialOperation extends VcResource<KeyMaterialOperation>.VcOperation {
    private final KeyMaterialService owner;
    private final KeyMaterialRepository repository;
    private final BlobStorageOperations source;
    private KeyStore snapshot;
    private final Set<String> changedAliases = new HashSet<>();

    KeyMaterialOperation(KeyMaterialRepository repository, BlobStorageOperations source, KeyStore snapshot) {
        repository.super(source.revision());
        this.owner = new KeyMaterialService(repository);
        this.repository = repository;
        this.source = source;
        this.snapshot = snapshot;
    }

    public synchronized KeyMaterialEntry read(KeyMaterialId id) throws GeneralSecurityException {
        requireOpen();
        return owner.describe(requireEntry(id));
    }

    public synchronized boolean contains(String alias) throws GeneralSecurityException {
        requireOpen();
        Entry entry = find(alias);
        return entry != null && !entry.deleted();
    }

    public synchronized void require(KeyMaterialId id, Set<KeyMaterialPurpose> purposes,
            KeyMaterialAlgorithm algorithm) throws GeneralSecurityException {
        KeyMaterialEntry entry = read(id);
        owner.validatePrepared(id.alias(), entry.content(), purposes, algorithm);
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
    protected synchronized VcRevision.Stored commitChanges(VcChangeDescription description) {
        requireOpen();
        try {
            VcRevision.Stored committed = repository.commit(source, snapshot, changedAliases, description);
            changedAliases.clear();
            return committed;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Override
    public synchronized void close() {
        if (snapshot == null) return;
        changedAliases.clear();
        snapshot = null;
        source.close();
    }

    private Entry find(String alias) throws GeneralSecurityException {
        return repository.find(snapshot, alias).orElse(null);
    }

    private Entry requireEntry(KeyMaterialId id) throws GeneralSecurityException {
        Entry latest = find(id.alias());
        if (latest != null) requireAvailable(latest);
        Entry entry = repository.find(snapshot, id).orElse(null);
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

    KeyMaterialId add(String alias, KeyStore.Entry entry, boolean deleted)
            throws GeneralSecurityException {
        Entry previous = find(alias);
        long next = previous == null ? 1 : Math.incrementExact(previous.version());
        repository.append(snapshot, new Entry(alias, entry, next, deleted));
        changedAliases.add(alias);
        return new KeyMaterialId(alias, next);
    }

    private void requireOpen() {
        if (snapshot == null) throw new IllegalStateException("Key material operation is closed");
        owner.requireOpen();
    }

}
