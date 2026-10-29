package pro.deta.orion.keymaterial;

import pro.deta.orion.keymaterial.KeyMaterialContentStore.Entry;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PKCS12Attribute;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.security.Key;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Validates material descriptors and prepares modifications against the store's logical revision.
 * Descriptor attributes identify persisted purposes, algorithms and descriptor versions and must remain stable.
 * @AiRule Read operations must use store snapshots without acquiring the service monitor.
 */
final class KeyMaterialService implements AutoCloseable {
    private static final String DESCRIPTOR_ATTRIBUTE_OID =
            "2.25.260349814076539099691640886169327825428";

    private final KeyMaterialContentStore store;
    private final KeyMaterialStorageCertificateFactory storageCertificateFactory;

    private KeyMaterialService(KeyMaterialContentStore store,
            KeyMaterialStorageCertificateFactory storageCertificateFactory) {
        if (store == null) throw new IllegalArgumentException("Key material store must not be null");
        if (storageCertificateFactory == null) {
            throw new IllegalArgumentException("Storage certificate factory must not be null");
        }
        this.store = store;
        this.storageCertificateFactory = storageCertificateFactory;
    }

    public static synchronized KeyMaterialService open(KeyMaterialContentStore store) {
        return open(store, new KeyMaterialStorageCertificateFactory());
    }

    public static synchronized KeyMaterialService open(KeyMaterialContentStore store,
            KeyMaterialStorageCertificateFactory storageCertificateFactory) {
        return new KeyMaterialService(store, storageCertificateFactory);
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
        if (fields.length != 3) throw metadataFailure(alias, "format");
        try {
            return parsePurposes(fields[0]).containsAll(requested);
        } catch (IllegalArgumentException failure) {
            throw new GeneralSecurityException("Invalid key material purposes: " + alias, failure);
        }
    }

    boolean hasDurableSnapshot() {
        return store.hasDurableSnapshot();
    }

    public KeyPair getKeyPair(String alias) throws GeneralSecurityException {
        KeyStore.Entry entry = requireEntry(alias);
        if (!(entry instanceof KeyStore.PrivateKeyEntry key)) {
            throw new GeneralSecurityException("Private key alias not found: " + alias);
        }
        Certificate[] chain = key.getCertificateChain();
        if (chain.length == 0) {
            throw new GeneralSecurityException("Private key alias has no certificate chain: " + alias);
        }
        return new KeyPair(chain[0].getPublicKey(), key.getPrivateKey());
    }

    public PrivateKey getPrivateKey(String alias) throws GeneralSecurityException {
        KeyStore.Entry entry = requireEntry(alias);
        if (!(entry instanceof KeyStore.PrivateKeyEntry key)) {
            throw new GeneralSecurityException("Private key alias not found: " + alias);
        }
        return key.getPrivateKey();
    }

    public Certificate[] getCertificateChain(String alias) throws GeneralSecurityException {
        KeyStore.Entry entry = requireEntry(alias);
        if (!(entry instanceof KeyStore.PrivateKeyEntry key)) {
            throw new GeneralSecurityException("Certificate chain alias not found: " + alias);
        }
        return key.getCertificateChain();
    }

    public X509Certificate getTrustedCertificate(KeyMaterialDescriptor descriptor)
            throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
        if (!descriptor.purposes().contains(KeyMaterialPurpose.TRUST_ANCHOR)) {
            throw new IllegalArgumentException("Trusted certificates require TRUST_ANCHOR purpose");
        }
        KeyStore.Entry entry = requireEntry(descriptor.alias());
        validatePrepared(entry, descriptor);
        return trustedCertificate(entry, descriptor.alias());
    }

    private static X509Certificate trustedCertificate(KeyStore.Entry entry, String alias)
            throws GeneralSecurityException {
        if (!(entry instanceof KeyStore.TrustedCertificateEntry trusted)
                || !(trusted.getTrustedCertificate() instanceof X509Certificate certificate)) {
            throw new GeneralSecurityException("Trusted certificate alias not found: " + alias);
        }
        return certificate;
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

    public void validateExisting(KeyMaterialDescriptor descriptor)
            throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
        Entry addition = readEntry(descriptor.alias());
        if (addition == null || addition.deleted()) {
            throw new GeneralSecurityException("Key material alias not found: " + descriptor.alias());
        }
        validatePrepared(addition.entry(), descriptor);
    }

    public List<KeyMaterialDescriptor> privateKeyDescriptors(
            Set<KeyMaterialPurpose> requiredPurposes) throws GeneralSecurityException {
        List<KeyMaterialDescriptor> descriptors = new ArrayList<>();
        for (KeyMaterialInventoryEntry entry : inventory(requiredPurposes)) {
            KeyMaterialDescriptor descriptor = entry.descriptor();
            if (descriptor.purposes().contains(KeyMaterialPurpose.TRUST_ANCHOR)
                    || descriptor.purposes().contains(KeyMaterialPurpose.CONFIGURATION_CIPHER)) continue;
            descriptors.add(descriptor);
        }
        return List.copyOf(descriptors);
    }

    public List<KeyMaterialInventoryEntry> inventory(Set<KeyMaterialPurpose> requiredPurposes)
            throws GeneralSecurityException {
        requireOpen();
        Set<KeyMaterialPurpose> requested = Set.copyOf(requiredPurposes);
        List<KeyMaterialInventoryEntry> entries = new ArrayList<>();
        for (Entry stored : store.entries()) {
            if (stored.deleted()) continue;
            String alias = stored.alias();
            KeyStore.Entry entry = stored.entry();
            boolean trusted = entry instanceof KeyStore.TrustedCertificateEntry;
            boolean privateKey = entry instanceof KeyStore.PrivateKeyEntry;
            boolean secretKey = entry instanceof KeyStore.SecretKeyEntry;
            if (!trusted && !privateKey && !secretKey) {
                throw new GeneralSecurityException("Unknown key material entry type: " + alias);
            }
            String metadata = attribute(entry.getAttributes(), DESCRIPTOR_ATTRIBUTE_OID);
            if (metadata == null) {
                throw new GeneralSecurityException("Typed key material metadata is missing for alias: " + alias);
            }
            String[] fields = metadata.split(";", -1);
            if (fields.length != 3) {
                throw new GeneralSecurityException("Typed key material metadata has invalid format: " + alias);
            }
            Set<KeyMaterialPurpose> purposes;
            KeyMaterialDescriptor descriptor;
            try {
                purposes = parsePurposes(fields[0]);
                descriptor = new KeyMaterialDescriptor(alias, purposes,
                        KeyMaterialAlgorithm.valueOf(fields[1]), Long.parseLong(fields[2]));
            } catch (IllegalArgumentException failure) {
                throw new GeneralSecurityException("Typed key material metadata has invalid format: " + alias,
                        failure);
            }
            if (trusted != purposes.contains(KeyMaterialPurpose.TRUST_ANCHOR)
                    || secretKey != purposes.contains(KeyMaterialPurpose.CONFIGURATION_CIPHER)) {
                throw new GeneralSecurityException("Key material entry type does not match purpose: " + alias);
            }
            if (!purposes.containsAll(requested)) continue;
            validatePrepared(entry, descriptor);
            Certificate[] chain;
            if (secretKey) {
                chain = new Certificate[0];
            } else if (trusted) {
                chain = new Certificate[]{trustedCertificate(entry, alias)};
            } else {
                chain = ((KeyStore.PrivateKeyEntry) entry).getCertificateChain();
            }
            String publicKey = secretKey ? null : publicKeyPem(chain[0].getPublicKey());
            List<KeyMaterialInventoryEntry.CertificateDetails> certificates = new ArrayList<>();
            if (!isStorageCertificate(chain)) {
                for (Certificate certificate : chain) {
                    if (!(certificate instanceof X509Certificate x509)) {
                        throw new GeneralSecurityException("Non-X509 certificate in key material: " + alias);
                    }
                    certificates.add(certificateDetails(x509));
                }
            }
            entries.add(new KeyMaterialInventoryEntry(descriptor,
                    publicKey, secretKey ? null : HexFormat.of().withDelimiter(":").formatHex(
                            MessageDigest.getInstance("SHA-256").digest(chain[0].getPublicKey().getEncoded())),
                    certificates));
        }
        return List.copyOf(entries);
    }

    private static String publicKeyPem(PublicKey key) {
        String encoded = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(key.getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----";
    }

    private static boolean isStorageCertificate(Certificate[] chain) {
        if (chain.length != 1 || !(chain[0] instanceof X509Certificate certificate)) {
            return false;
        }
        return certificate.getBasicConstraints() < 0
                && certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())
                && certificate.getSubjectX500Principal().getName()
                        .contains("O=" + KeyMaterialConstants.STORAGE_CERTIFICATE_ORGANIZATION);
    }

    private static KeyMaterialInventoryEntry.CertificateDetails certificateDetails(X509Certificate certificate)
            throws GeneralSecurityException {
        byte[] fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        List<String> dnsNames = new ArrayList<>();
        Collection<List<?>> alternatives = certificate.getSubjectAlternativeNames();
        if (alternatives != null) {
            for (List<?> alternative : alternatives) {
                if (Integer.valueOf(2).equals(alternative.get(0))
                        && alternative.get(1) instanceof String name) {
                    dnsNames.add(name);
                }
            }
        }
        return new KeyMaterialInventoryEntry.CertificateDetails(
                certificate.getSubjectX500Principal().getName(),
                dnsNames,
                certificate.getIssuerX500Principal().getName(),
                certificate.getSerialNumber().toString(16),
                certificate.getNotBefore().toInstant().toString(),
                certificate.getNotAfter().toInstant().toString(),
                HexFormat.of().withDelimiter(":").formatHex(fingerprint));
    }

    synchronized KeyStore.Entry prepareKey(KeyMaterialDescriptor descriptor, int keySize)
            throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
        if (descriptor.purposes().contains(KeyMaterialPurpose.TRUST_ANCHOR)) {
            throw new IllegalArgumentException("Trust anchors cannot be private key material");
        }
        if (descriptor.algorithm() == KeyMaterialAlgorithm.AES) {
            if (keySize < 1) throw new IllegalArgumentException("Secret key size must be positive");
            KeyGenerator generator = KeyGenerator.getInstance(descriptor.algorithm().keyAlgorithm());
            generator.init(keySize);
            return prepareSecretKey(descriptor, generator.generateKey());
        }
        if (keySize < 0) throw new IllegalArgumentException("Key size must not be negative");
        KeyPairGenerator generator = KeyPairGenerator.getInstance(descriptor.algorithm().keyAlgorithm());
        if (keySize > 0) generator.initialize(keySize);
        return preparePrivateKey(descriptor, generator.generateKeyPair());
    }

    synchronized KeyStore.PrivateKeyEntry preparePrivateKey(KeyMaterialDescriptor descriptor, KeyPair pair)
            throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
        if (descriptor.purposes().contains(KeyMaterialPurpose.TRUST_ANCHOR)) {
            throw new IllegalArgumentException("Trust anchors cannot be private key material");
        }
        if (descriptor.algorithm() == KeyMaterialAlgorithm.AES) {
            throw new IllegalArgumentException("Private key material cannot use AES");
        }
        if (pair == null || pair.getPrivate() == null || pair.getPublic() == null) {
            throw new IllegalArgumentException("Key pair must include public and private keys");
        }
        requireAlgorithm(descriptor, pair.getPrivate().getAlgorithm());
        requireAlgorithm(descriptor, pair.getPublic().getAlgorithm());
        X509Certificate certificate = storageCertificateFactory.create(
                descriptor.alias(), formatPurposes(descriptor.purposes()), pair);
        return prepareCertificateChain(descriptor, pair, List.of(certificate));
    }

    synchronized KeyStore.PrivateKeyEntry prepareCertificateChain(KeyMaterialDescriptor descriptor, KeyPair pair,
            List<? extends Certificate> chain) throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
        if (descriptor.purposes().contains(KeyMaterialPurpose.TRUST_ANCHOR)) {
            throw new IllegalArgumentException("Trust anchors cannot be private key material");
        }
        Certificate[] certificates = requireCertificateChain(chain);
        requireMatchingLeaf(descriptor.alias(), pair, certificates);
        return new KeyStore.PrivateKeyEntry(pair.getPrivate(), certificates, descriptorAttributes(descriptor));
    }

    synchronized KeyStore.SecretKeyEntry prepareSecretKey(KeyMaterialDescriptor descriptor, SecretKey key)
            throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
        if (!descriptor.purposes().contains(KeyMaterialPurpose.CONFIGURATION_CIPHER)) {
            throw new IllegalArgumentException("Secret key material must be an AES configuration cipher");
        }
        if (key == null) throw new IllegalArgumentException("Secret key must not be null");
        requireAlgorithm(descriptor, key.getAlgorithm());
        Cipher wrappingCipher = Cipher.getInstance(ConfigurationSecretEnvelopeCodec.AES_WRAP);
        wrappingCipher.init(Cipher.WRAP_MODE, key);
        return new KeyStore.SecretKeyEntry(key, descriptorAttributes(descriptor));
    }

    synchronized KeyStore.TrustedCertificateEntry prepareTrustedCertificate(KeyMaterialDescriptor descriptor,
            X509Certificate certificate) throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
        if (!descriptor.purposes().contains(KeyMaterialPurpose.TRUST_ANCHOR)) {
            throw new IllegalArgumentException("Trusted certificates require TRUST_ANCHOR purpose");
        }
        if (certificate == null) throw new IllegalArgumentException("Trusted certificate must not be null");
        if (certificate.getBasicConstraints() < 0) {
            throw new GeneralSecurityException("Trusted certificate must be a certificate authority");
        }
        certificate.checkValidity();
        requireAlgorithm(descriptor, certificate.getPublicKey().getAlgorithm());
        return new KeyStore.TrustedCertificateEntry(certificate, descriptorAttributes(descriptor));
    }

    void validatePrepared(KeyStore.Entry entry, KeyMaterialDescriptor descriptor)
            throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
        if (descriptor.purposes().contains(KeyMaterialPurpose.TRUST_ANCHOR)) {
            if (!(entry instanceof KeyStore.TrustedCertificateEntry trusted)) {
                throw new GeneralSecurityException("Key material entry type does not match purpose");
            }
            requireAlgorithm(descriptor, trusted.getTrustedCertificate().getPublicKey().getAlgorithm());
        } else if (descriptor.purposes().contains(KeyMaterialPurpose.CONFIGURATION_CIPHER)) {
            if (!(entry instanceof KeyStore.SecretKeyEntry secret)) {
                throw new GeneralSecurityException("Key material entry type does not match purpose");
            }
            requireAlgorithm(descriptor, secret.getSecretKey().getAlgorithm());
        } else {
            if (!(entry instanceof KeyStore.PrivateKeyEntry key)) {
                throw new GeneralSecurityException("Key material entry type does not match purpose");
            }
            requireAlgorithm(descriptor, key.getPrivateKey().getAlgorithm());
        }
        validateMetadata(descriptor.alias(), attribute(entry.getAttributes(), DESCRIPTOR_ATTRIBUTE_OID),
                descriptor.purposes(), descriptor.algorithm(), descriptor.version());
    }

    Entry readEntry(String alias) throws GeneralSecurityException {
        return store.find(alias).orElse(null);
    }

    private static String attribute(Set<KeyStore.Entry.Attribute> attributes, String oid) {
        for (KeyStore.Entry.Attribute attribute : attributes) {
            if (oid.equals(attribute.getName())) return attribute.getValue();
        }
        return null;
    }

    private static void validateMetadata(String alias, String metadata, Set<KeyMaterialPurpose> requiredPurposes,
            KeyMaterialAlgorithm algorithm, long version)
            throws GeneralSecurityException {
        if (metadata == null) throw new GeneralSecurityException("Typed key material metadata is missing: " + alias);
        String[] fields = metadata.split(";", -1);
        if (fields.length != 3) throw metadataFailure(alias, "format");
        try {
            if (!parsePurposes(fields[0]).containsAll(requiredPurposes)) throw metadataFailure(alias, "purpose");
        } catch (IllegalArgumentException failure) {
            throw new GeneralSecurityException("Invalid key material purposes: " + alias, failure);
        }
        requireMetadataField(alias, "algorithm", algorithm.name(), fields[1]);
        requireMetadataField(alias, "version", Long.toString(version), fields[2]);
    }

    long revision() {
        return store.revision();
    }

    synchronized KeyMaterialModification modify(long expectedRevision) throws KeyMaterialStoreConflictException {
        requireOpen();
        if (store.revision() != expectedRevision) {
            throw new KeyMaterialStoreConflictException("Key material changed before modify");
        }
        return new KeyMaterialModification(this, expectedRevision);
    }

    void apply(List<Entry> additions, long expectedRevision) throws IOException {
        store.apply(expectedRevision, additions);
    }

    void prepareConfigurationCipher(KeyMaterialDescriptor descriptor) throws GeneralSecurityException {
        if (containsAlias(descriptor.alias(), Set.of())) return;
        try {
            store.addIfAbsent(descriptor.alias(), prepareKey(descriptor, 256));
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

    SecretKey secretKey(String alias) throws GeneralSecurityException {
        KeyStore.Entry entry = requireEntry(alias);
        if (!(entry instanceof KeyStore.SecretKeyEntry secret)) {
            throw new GeneralSecurityException("Secret key alias not found: " + alias);
        }
        return secret.getSecretKey();
    }

    private static Set<KeyStore.Entry.Attribute> descriptorAttributes(KeyMaterialDescriptor descriptor) {
        String value = formatPurposes(descriptor.purposes())
                + ";" + descriptor.algorithm().name()
                + ";" + descriptor.version();
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

    private static void requireAlgorithm(KeyMaterialDescriptor descriptor, String actual)
            throws GeneralSecurityException {
        if (!descriptor.algorithm().acceptsKeyAlgorithm(actual)) {
            throw new GeneralSecurityException(
                    "Key material algorithm does not match alias " + descriptor.alias()
                            + ": expected " + descriptor.algorithm().keyAlgorithm() + ", found " + actual);
        }
    }

    private static void requireDescriptor(KeyMaterialDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("Key material descriptor must not be null");
        }
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

    private KeyStore.Entry requireEntry(String alias) throws GeneralSecurityException {
        Entry entry = store.find(alias).orElseThrow(
                () -> new GeneralSecurityException("Key material alias not found: " + alias));
        if (entry.deleted()) throw new GeneralSecurityException("Key material alias not found: " + alias);
        return entry.entry();
    }
}
