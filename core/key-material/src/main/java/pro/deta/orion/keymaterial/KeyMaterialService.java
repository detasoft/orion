package pro.deta.orion.keymaterial;

import pro.deta.orion.storage.BlobStorageConflictException;

import pro.deta.orion.keymaterial.KeyMaterialRepository.Entry;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PKCS12Attribute;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.security.Key;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Validates material metadata and prepares modifications against the content-source revision.
 * Descriptor attributes persist purposes and algorithms; the append-only entry owns its numeric version.
 * @AiRule Read operations must use store snapshots without acquiring the service monitor.
 */
final class KeyMaterialService implements AutoCloseable {
    private static final String DESCRIPTOR_ATTRIBUTE_OID =
            "2.25.260349814076539099691640886169327825428";

    private final KeyMaterialRepository store;
    private final KeyMaterialStorageCertificateFactory storageCertificateFactory;

    KeyMaterialService(KeyMaterialRepository store) {
        this(store, new KeyMaterialStorageCertificateFactory());
    }

    KeyMaterialService(KeyMaterialRepository store,
            KeyMaterialStorageCertificateFactory storageCertificateFactory) {
        if (store == null) throw new IllegalArgumentException("Key material store must not be null");
        if (storageCertificateFactory == null) {
            throw new IllegalArgumentException("Storage certificate factory must not be null");
        }
        this.store = store;
        this.storageCertificateFactory = storageCertificateFactory;
    }

    public boolean containsAlias(String alias, Set<KeyMaterialPurpose> requiredPurposes)
            throws GeneralSecurityException {
        requireOpen();
        requireAlias(alias);
        Set<KeyMaterialPurpose> requested = Set.copyOf(requiredPurposes);
        Entry latest = store.find(alias).orElse(null);
        if (latest == null || latest.deleted()) return false;
        if (requested.isEmpty()) return true;
        String metadata = attribute(latest.entry().getAttributes(), DESCRIPTOR_ATTRIBUTE_OID);
        if (metadata == null) throw new GeneralSecurityException("Typed key material metadata is missing: " + alias);
        String[] fields = metadata.split(";", -1);
        if (fields.length != 2) throw metadataFailure(alias, "format");
        try {
            return parsePurposes(fields[0]).containsAll(requested);
        } catch (IllegalArgumentException failure) {
            throw new GeneralSecurityException("Invalid key material purposes: " + alias, failure);
        }
    }

    boolean hasDurableSnapshot() {
        return store.hasDurableSnapshot();
    }

    private static void requireMatchingLeaf(
            String alias,
            KeyPair keyPair,
            Certificate[] certificateChain) throws GeneralSecurityException {
        if (keyPair == null || keyPair.getPrivate() == null || keyPair.getPublic() == null) {
            throw new IllegalArgumentException("Key pair must include public and private keys");
        }
        if (certificateChain == null || certificateChain.length == 0) {
            throw new IllegalArgumentException("Certificate chain must not be empty");
        }
        if (!publicKeysMatch(keyPair.getPublic(), certificateChain[0].getPublicKey())) {
            throw new GeneralSecurityException(
                    "Certificate public key does not match private key alias: " + alias);
        }
    }

    public void validateExisting(KeyMaterialId id, Set<KeyMaterialPurpose> purposes,
            KeyMaterialAlgorithm algorithm) throws GeneralSecurityException {
        KeyMaterialEntry entry = read(id);
        validatePrepared(id.alias(), entry.content(), purposes, algorithm);
    }

    public List<KeyMaterialId> inventory() throws GeneralSecurityException {
        requireOpen();
        List<KeyMaterialId> result = new ArrayList<>();
        for (Entry stored : store.entries()) {
            if (stored.deleted()) continue;
            KeyMaterialId id = new KeyMaterialId(stored.alias(), stored.version());
            describe(stored);
            result.add(id);
        }
        return List.copyOf(result);
    }

    public KeyMaterialEntry read(KeyMaterialId id) throws GeneralSecurityException {
        Entry stored = store.find(id).orElseThrow(
                () -> new GeneralSecurityException("Key material version not found: " + id));
        KeyMaterialEntry entry = describe(stored);
        return new KeyMaterialEntry(id, entry.purposes(), entry.algorithm(), entry.content());
    }

    KeyMaterialEntry describe(Entry stored) throws GeneralSecurityException {
        String metadata = attribute(stored.entry().getAttributes(), DESCRIPTOR_ATTRIBUTE_OID);
        if (metadata == null) {
            throw new GeneralSecurityException("Typed key material metadata is missing: " + stored.alias());
        }
        String[] fields = metadata.split(";", -1);
        if (fields.length != 2) throw metadataFailure(stored.alias(), "format");
        try {
            Set<KeyMaterialPurpose> purposes = parsePurposes(fields[0]);
            KeyMaterialAlgorithm algorithm = KeyMaterialAlgorithm.valueOf(fields[1]);
            KeyMaterialEntry.validateMetadata(purposes, algorithm);
            validatePrepared(stored.alias(), stored.entry(), purposes, algorithm);
            return new KeyMaterialEntry(new KeyMaterialId(stored.alias(), stored.version()),
                    purposes, algorithm, stored.entry());
        } catch (IllegalArgumentException failure) {
            throw new GeneralSecurityException("Invalid key material metadata: " + stored.alias(), failure);
        }
    }

    synchronized KeyStore.Entry prepareKey(String alias, Set<KeyMaterialPurpose> purposes, KeyMaterialAlgorithm algorithm, int keySize)
            throws GeneralSecurityException {
        requireOpen();
        requireMaterial(alias, purposes, algorithm);
        if (purposes.contains(KeyMaterialPurpose.TRUST_ANCHOR)) {
            throw new IllegalArgumentException("Trust anchors cannot be private key material");
        }
        if (algorithm == KeyMaterialAlgorithm.AES) {
            if (keySize < 1) throw new IllegalArgumentException("Secret key size must be positive");
            KeyGenerator generator = KeyGenerator.getInstance(algorithm.keyAlgorithm());
            generator.init(keySize);
            return prepareSecretKey(alias, purposes, algorithm, generator.generateKey());
        }
        if (keySize < 0) throw new IllegalArgumentException("Key size must not be negative");
        KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm.keyAlgorithm());
        if (keySize > 0) generator.initialize(keySize);
        return preparePrivateKey(alias, purposes, algorithm, generator.generateKeyPair());
    }

    synchronized KeyStore.PrivateKeyEntry preparePrivateKey(String alias, Set<KeyMaterialPurpose> purposes, KeyMaterialAlgorithm algorithm, KeyPair pair)
            throws GeneralSecurityException {
        requireOpen();
        requireMaterial(alias, purposes, algorithm);
        if (purposes.contains(KeyMaterialPurpose.TRUST_ANCHOR)) {
            throw new IllegalArgumentException("Trust anchors cannot be private key material");
        }
        if (algorithm == KeyMaterialAlgorithm.AES) {
            throw new IllegalArgumentException("Private key material cannot use AES");
        }
        if (pair == null || pair.getPrivate() == null || pair.getPublic() == null) {
            throw new IllegalArgumentException("Key pair must include public and private keys");
        }
        requireAlgorithm(alias, algorithm, pair.getPrivate().getAlgorithm());
        requireAlgorithm(alias, algorithm, pair.getPublic().getAlgorithm());
        X509Certificate certificate = storageCertificateFactory.create(
                alias, formatPurposes(purposes), pair);
        return prepareCertificateChain(alias, purposes, algorithm, pair, List.of(certificate));
    }

    synchronized KeyStore.PrivateKeyEntry prepareCertificateChain(String alias, Set<KeyMaterialPurpose> purposes, KeyMaterialAlgorithm algorithm, KeyPair pair,
            List<? extends Certificate> chain) throws GeneralSecurityException {
        requireOpen();
        requireMaterial(alias, purposes, algorithm);
        if (purposes.contains(KeyMaterialPurpose.TRUST_ANCHOR)) {
            throw new IllegalArgumentException("Trust anchors cannot be private key material");
        }
        Certificate[] certificates = requireCertificateChain(chain);
        requireMatchingLeaf(alias, pair, certificates);
        return new KeyStore.PrivateKeyEntry(pair.getPrivate(), certificates, materialAttributes(purposes, algorithm));
    }

    synchronized KeyStore.SecretKeyEntry prepareSecretKey(String alias, Set<KeyMaterialPurpose> purposes, KeyMaterialAlgorithm algorithm, SecretKey key)
            throws GeneralSecurityException {
        requireOpen();
        requireMaterial(alias, purposes, algorithm);
        if (!purposes.contains(KeyMaterialPurpose.CONFIGURATION_CIPHER)) {
            throw new IllegalArgumentException("Secret key material must be an AES configuration cipher");
        }
        if (key == null) throw new IllegalArgumentException("Secret key must not be null");
        requireAlgorithm(alias, algorithm, key.getAlgorithm());
        Cipher wrappingCipher = Cipher.getInstance(ConfigurationSecretEnvelopeCodec.AES_WRAP);
        wrappingCipher.init(Cipher.WRAP_MODE, key);
        return new KeyStore.SecretKeyEntry(key, materialAttributes(purposes, algorithm));
    }

    synchronized KeyStore.TrustedCertificateEntry prepareTrustedCertificate(String alias, Set<KeyMaterialPurpose> purposes, KeyMaterialAlgorithm algorithm,
            X509Certificate certificate) throws GeneralSecurityException {
        requireOpen();
        requireMaterial(alias, purposes, algorithm);
        if (!purposes.contains(KeyMaterialPurpose.TRUST_ANCHOR)) {
            throw new IllegalArgumentException("Trusted certificates require TRUST_ANCHOR purpose");
        }
        if (certificate == null) throw new IllegalArgumentException("Trusted certificate must not be null");
        if (certificate.getBasicConstraints() < 0) {
            throw new GeneralSecurityException("Trusted certificate must be a certificate authority");
        }
        certificate.checkValidity();
        requireAlgorithm(alias, algorithm, certificate.getPublicKey().getAlgorithm());
        return new KeyStore.TrustedCertificateEntry(certificate, materialAttributes(purposes, algorithm));
    }

    void validatePrepared(String alias, KeyStore.Entry entry, Set<KeyMaterialPurpose> purposes,
            KeyMaterialAlgorithm algorithm)
            throws GeneralSecurityException {
        requireOpen();
        requireMaterial(alias, purposes, algorithm);
        if (purposes.contains(KeyMaterialPurpose.TRUST_ANCHOR)) {
            if (!(entry instanceof KeyStore.TrustedCertificateEntry trusted)) {
                throw new GeneralSecurityException("Key material entry type does not match purpose");
            }
            requireAlgorithm(alias, algorithm, trusted.getTrustedCertificate().getPublicKey().getAlgorithm());
        } else if (purposes.contains(KeyMaterialPurpose.CONFIGURATION_CIPHER)) {
            if (!(entry instanceof KeyStore.SecretKeyEntry secret)) {
                throw new GeneralSecurityException("Key material entry type does not match purpose");
            }
            requireAlgorithm(alias, algorithm, secret.getSecretKey().getAlgorithm());
        } else {
            if (!(entry instanceof KeyStore.PrivateKeyEntry key)) {
                throw new GeneralSecurityException("Key material entry type does not match purpose");
            }
            requireAlgorithm(alias, algorithm, key.getPrivateKey().getAlgorithm());
        }
        validateMetadata(alias, attribute(entry.getAttributes(), DESCRIPTOR_ATTRIBUTE_OID),
                purposes, algorithm);
    }

    Entry readEntry(String alias) throws GeneralSecurityException {
        return store.find(alias).orElse(null);
    }

    Entry readEntry(KeyMaterialId id) throws GeneralSecurityException {
        return store.find(id).orElse(null);
    }

    private static String attribute(Set<KeyStore.Entry.Attribute> attributes, String oid) {
        for (KeyStore.Entry.Attribute attribute : attributes) {
            if (oid.equals(attribute.getName())) return attribute.getValue();
        }
        return null;
    }

    private static void validateMetadata(String alias, String metadata, Set<KeyMaterialPurpose> requiredPurposes,
            KeyMaterialAlgorithm algorithm)
            throws GeneralSecurityException {
        if (metadata == null) throw new GeneralSecurityException("Typed key material metadata is missing: " + alias);
        String[] fields = metadata.split(";", -1);
        if (fields.length != 2) throw metadataFailure(alias, "format");
        try {
            if (!parsePurposes(fields[0]).containsAll(requiredPurposes)) throw metadataFailure(alias, "purpose");
        } catch (IllegalArgumentException failure) {
            throw new GeneralSecurityException("Invalid key material purposes: " + alias, failure);
        }
        requireMetadataField(alias, "algorithm", algorithm.name(), fields[1]);
    }

    String revision() {
        return store.revision();
    }

    synchronized KeyMaterialModification modify(String expectedRevision)
            throws BlobStorageConflictException {
        requireOpen();
        if (!Objects.equals(store.revision(), expectedRevision)) {
            throw new BlobStorageConflictException("Key material changed before modify");
        }
        return new KeyMaterialModification(this, expectedRevision);
    }

    void apply(List<Entry> additions, String expectedRevision) throws IOException {
        store.apply(expectedRevision, additions);
    }

    void prepareConfigurationCipher(KeyMaterialId id) throws GeneralSecurityException {
        if (containsAlias(id.alias(), Set.of())) return;
        if (id.version() != 1) throw new GeneralSecurityException("Key material version not found: " + id);
        try {
            store.addIfAbsent(id.alias(), prepareKey(id.alias(),
                    Set.of(KeyMaterialPurpose.CONFIGURATION_CIPHER), KeyMaterialAlgorithm.AES, 256));
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            close();
            throw new GeneralSecurityException("Cannot persist configuration cipher", failure);
        }
    }

    void reload(String requestedRevision) throws IOException, GeneralSecurityException {
        store.reload(requestedRevision);
    }

    @Override
    public synchronized void close() {
        store.close();
    }

    private static void requireAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("Key material alias must not be empty");
        }
    }

    private static Set<KeyStore.Entry.Attribute> materialAttributes(Set<KeyMaterialPurpose> purposes,
            KeyMaterialAlgorithm algorithm) {
        String value = formatPurposes(purposes)
                + ";" + algorithm.name();
        return Set.of(new PKCS12Attribute(DESCRIPTOR_ATTRIBUTE_OID, value));
    }

    private static String formatPurposes(Set<KeyMaterialPurpose> purposes) {
        StringJoiner encoded = new StringJoiner(",");
        for (KeyMaterialPurpose purpose : KeyMaterialPurpose.values()) {
            if (purposes.contains(purpose)) encoded.add(purpose.name());
        }
        return encoded.toString();
    }

    private static Set<KeyMaterialPurpose> parsePurposes(String encoded) {
        EnumSet<KeyMaterialPurpose> purposes = EnumSet.noneOf(KeyMaterialPurpose.class);
        for (String value : encoded.split(",", -1)) {
            if (!purposes.add(KeyMaterialPurpose.valueOf(value))) {
                throw new IllegalArgumentException("Duplicate key material purpose");
            }
        }
        return purposes;
    }

    private static void requireMetadataField(
            String alias,
            String field,
            String expected,
            String actual) throws GeneralSecurityException {
        if (!expected.equals(actual)) {
            throw metadataFailure(alias, field);
        }
    }

    private static GeneralSecurityException metadataFailure(String alias, String field) {
        return new GeneralSecurityException("Key material " + field + " does not match alias: " + alias);
    }

    private static void requireAlgorithm(String alias, KeyMaterialAlgorithm algorithm, String actual)
            throws GeneralSecurityException {
        if (!algorithm.acceptsKeyAlgorithm(actual)) {
            throw new GeneralSecurityException(
                    "Key material algorithm does not match alias " + alias
                            + ": expected " + algorithm.keyAlgorithm() + ", found " + actual);
        }
    }

    private static void requireMaterial(String alias, Set<KeyMaterialPurpose> purposes,
            KeyMaterialAlgorithm algorithm) {
        requireAlias(alias);
        KeyMaterialEntry.validateMetadata(purposes, algorithm);
    }

    private static Certificate[] requireCertificateChain(List<? extends Certificate> certificateChain) {
        if (certificateChain == null || certificateChain.isEmpty()) {
            throw new IllegalArgumentException("Certificate chain must not be empty");
        }
        Certificate[] certificates = certificateChain.toArray(Certificate[]::new);
        for (Certificate certificate : certificates) {
            if (certificate == null) {
                throw new IllegalArgumentException("Certificate chain must not contain null certificates");
            }
        }
        return certificates;
    }

    private static boolean publicKeysMatch(PublicKey expected, PublicKey actual) {
        if (expected == null || actual == null) {
            return false;
        }
        byte[] expectedEncoded = expected.getEncoded();
        byte[] actualEncoded = actual.getEncoded();
        if (expectedEncoded != null && actualEncoded != null) {
            return expected.getAlgorithm().equals(actual.getAlgorithm())
                    && Arrays.equals(expectedEncoded, actualEncoded);
        }
        return expected.equals(actual);
    }

    void requireOpen() {
        store.requireOpen();
    }

}
