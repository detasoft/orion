package pro.deta.orion.keymaterial;

import pro.deta.orion.event.type.OrionEventPublisher;
import pro.deta.orion.keymaterial.KeyMaterialModification.Addition;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.security.Key;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Serializes local saves separately from state access so persistence and event publication can wait
 * without holding the state monitor needed by reload handlers.
 * @AiRule All public methods in this class must be synchronized.
 */
final class KeyMaterialService implements AutoCloseable {
    private static final String LOCAL_KEY_ID_ATTRIBUTE_OID = "1.2.840.113549.1.9.21";
    private static final String DESCRIPTOR_ATTRIBUTE_OID =
            "2.25.260349814076539099691640886169327825428";
    private static final String ADDITION_ATTRIBUTE_OID =
            "2.25.260349814076539099691640886169327826429";
    private final Object writes = new Object();
    private final KeyMaterialContentStore store;
    private final KeyMaterialOptions options;
    private final KeyMaterialStorageCertificateFactory storageCertificateFactory;
    private final OrionEventPublisher eventPublisher;
    private final KeyStore keyStore;
    private String version;
    private long revision;
    private boolean closed;

    private KeyMaterialService(
            KeyMaterialContentStore store,
            KeyMaterialOptions options,
            KeyMaterialStorageCertificateFactory storageCertificateFactory,
            OrionEventPublisher eventPublisher,
            KeyStore keyStore,
            String version) throws GeneralSecurityException {
        this.store = store;
        this.options = options;
        this.storageCertificateFactory = storageCertificateFactory;
        this.eventPublisher = eventPublisher;
        this.keyStore = keyStore;
        this.version = version;
        for (String alias : physicalAliases()) {
            revision = Math.max(revision, entryVersion(alias).kmsVersion());
        }
    }

    public static synchronized KeyMaterialService open(
            KeyMaterialContentStore store,
            KeyMaterialOptions options)
            throws IOException, GeneralSecurityException {
        return open(store, options, new KeyMaterialStorageCertificateFactory());
    }

    public static synchronized KeyMaterialService open(
            KeyMaterialContentStore store,
            KeyMaterialOptions options,
            KeyMaterialStorageCertificateFactory storageCertificateFactory)
            throws IOException, GeneralSecurityException {
        return open(store, options, storageCertificateFactory, event -> {});
    }

    public static synchronized KeyMaterialService open(
            KeyMaterialContentStore store,
            KeyMaterialOptions options,
            KeyMaterialStorageCertificateFactory storageCertificateFactory,
            OrionEventPublisher eventPublisher)
            throws IOException, GeneralSecurityException {
        if (store == null) {
            throw new IllegalArgumentException("Key material store must not be null");
        }
        if (options == null) {
            throw new IllegalArgumentException("Key material options must not be null");
        }
        if (storageCertificateFactory == null) {
            throw new IllegalArgumentException("Storage certificate factory must not be null");
        }
        if (eventPublisher == null) {
            throw new IllegalArgumentException("Orion event publisher must not be null");
        }
        KeyMaterialOptions ownedOptions = options.copy();

        try {
            KeyStore keyStore = KeyStore.getInstance(ownedOptions.type());
            Optional<KeyMaterialSnapshot> snapshot = store.read();
            char[] password = ownedOptions.password();
            try {
                if (snapshot.isPresent()) {
                    byte[] serialized = snapshot.get().bytes();
                    try {
                        keyStore.load(new ByteArrayInputStream(serialized), password);
                    } finally {
                        Arrays.fill(serialized, (byte) 0);
                    }
                    return new KeyMaterialService(
                            store,
                            ownedOptions,
                            storageCertificateFactory,
                            eventPublisher,
                            keyStore,
                            snapshot.get().version());
                }
                keyStore.load(null, password);
                return new KeyMaterialService(
                        store,
                        ownedOptions,
                        storageCertificateFactory,
                        eventPublisher,
                        keyStore,
                        null);
            } finally {
                clear(password);
            }
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            ownedOptions.close();
            try {
                store.close();
            } catch (RuntimeException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    public synchronized boolean containsAlias(String alias, Set<KeyMaterialPurpose> requiredPurposes) throws GeneralSecurityException {
        requireOpen();
        requireAlias(alias);
        Set<KeyMaterialPurpose> requested = Set.copyOf(requiredPurposes);
        EntryVersion latest = findVersion(alias);
        if (latest == null || latest.deleted()) return false;
        if (requested.isEmpty()) return true;
        String[] fields = descriptorMetadata(alias).split(";", -1);
        if (fields.length != 3) throw metadataFailure(alias, "format");
        try {
            return parsePurposes(fields[0]).containsAll(requested);
        } catch (IllegalArgumentException failure) {
            throw new GeneralSecurityException("Invalid key material purposes: " + alias, failure);
        }
    }

    synchronized boolean hasDurableSnapshot() {
        requireOpen();
        return version != null;
    }

    public synchronized KeyPair getKeyPair(String alias) throws GeneralSecurityException {
        requireOpen();
        PrivateKey privateKey = getPrivateKey(alias);
        Certificate[] chain = getCertificateChain(alias);
        if (chain.length == 0) {
            throw new GeneralSecurityException("Private key alias has no certificate chain: " + alias);
        }
        PublicKey publicKey = chain[0].getPublicKey();
        return new KeyPair(publicKey, privateKey);
    }

    public synchronized PrivateKey getPrivateKey(String alias) throws GeneralSecurityException {
        requireOpen();
        requireAlias(alias);
        char[] password = options.password();
        try {
            PrivateKey privateKey = (PrivateKey) keyStore.getKey(materialAlias(alias), password);
            if (privateKey == null) {
                throw new GeneralSecurityException("Private key alias not found: " + alias);
            }
            return privateKey;
        } finally {
            clear(password);
        }
    }

    public synchronized Certificate[] getCertificateChain(String alias) throws GeneralSecurityException {
        requireOpen();
        requireAlias(alias);
        Certificate[] chain = keyStore.getCertificateChain(materialAlias(alias));
        if (chain == null) {
            throw new GeneralSecurityException("Certificate chain alias not found: " + alias);
        }
        return Arrays.copyOf(chain, chain.length);
    }

    public synchronized X509Certificate getTrustedCertificate(TrustedCertificateDescriptor descriptor)
            throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
        validateExisting(descriptor);
        String alias = descriptor.alias();
        return trustedCertificate(alias);
    }

    private X509Certificate trustedCertificate(String alias) throws GeneralSecurityException {
        requireAlias(alias);
        if (!keyStore.entryInstanceOf(materialAlias(alias), KeyStore.TrustedCertificateEntry.class)) {
            throw new GeneralSecurityException("Trusted certificate alias not found: " + alias);
        }
        Certificate certificate = keyStore.getCertificate(materialAlias(alias));
        if (!(certificate instanceof X509Certificate x509Certificate)) {
            throw new GeneralSecurityException("Trusted certificate is not X.509: " + alias);
        }
        return x509Certificate;
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

    public synchronized void validateExisting(KeyMaterialDescriptor descriptor)
            throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
        Addition addition = readAddition(descriptor.alias());
        if (addition == null || addition.deleted()) {
            throw new GeneralSecurityException("Key material alias not found: " + descriptor.alias());
        }
        validatePrepared(addition.entry(), descriptor);
    }

    public synchronized List<KeyMaterialDescriptor> privateKeyDescriptors(
            Set<KeyMaterialPurpose> requiredPurposes) throws GeneralSecurityException {
        requireOpen();
        Set<KeyMaterialPurpose> requested = Set.copyOf(requiredPurposes);
        List<String> aliases = availableAliases();
        List<KeyMaterialDescriptor> descriptors = new ArrayList<>();
        for (String alias : aliases) {
            if (!keyStore.entryInstanceOf(materialAlias(alias), KeyStore.PrivateKeyEntry.class)) {
                continue;
            }
            String metadata = descriptorMetadataIfPresent(alias);
            if (metadata == null) {
                continue;
            }
            String[] fields = metadata.split(";", -1);
            if (fields.length != 3) {
                throw new GeneralSecurityException("Typed key material metadata has invalid format: " + alias);
            }
            KeyMaterialDescriptor descriptor;
            try {
                Set<KeyMaterialPurpose> purposes = parsePurposes(fields[0]);
                if (!purposes.containsAll(requested)) continue;
                descriptor = new KeyMaterialDescriptor(
                        alias,
                        purposes,
                        KeyMaterialAlgorithm.valueOf(fields[1]),
                        Long.parseLong(fields[2]));
            } catch (IllegalArgumentException failure) {
                throw new GeneralSecurityException(
                        "Typed key material metadata has invalid format: " + alias,
                        failure);
            }
            validateExisting(descriptor);
            descriptors.add(descriptor);
        }
        return List.copyOf(descriptors);
    }

    public synchronized List<KeyMaterialInventoryEntry> inventory(Set<KeyMaterialPurpose> requiredPurposes)
            throws GeneralSecurityException {
        requireOpen();
        Set<KeyMaterialPurpose> requested = Set.copyOf(requiredPurposes);
        List<String> aliases = availableAliases();
        List<KeyMaterialInventoryEntry> entries = new ArrayList<>();
        for (String alias : aliases) {
            boolean trusted = keyStore.entryInstanceOf(materialAlias(alias), KeyStore.TrustedCertificateEntry.class);
            boolean privateKey = keyStore.entryInstanceOf(materialAlias(alias), KeyStore.PrivateKeyEntry.class);
            boolean secretKey = keyStore.entryInstanceOf(materialAlias(alias), KeyStore.SecretKeyEntry.class);
            if (!trusted && !privateKey && !secretKey) {
                throw new GeneralSecurityException("Unknown key material entry type: " + alias);
            }
            String metadata = descriptorMetadata(alias);
            String[] fields = metadata.split(";", -1);
            if (fields.length != 3) {
                throw new GeneralSecurityException("Typed key material metadata has invalid format: " + alias);
            }
            Set<KeyMaterialPurpose> purposes;
            KeyMaterialAlgorithm algorithm;
            long version;
            try {
                purposes = parsePurposes(fields[0]);
                algorithm = KeyMaterialAlgorithm.valueOf(fields[1]);
                version = Long.parseLong(fields[2]);
            } catch (IllegalArgumentException failure) {
                throw new GeneralSecurityException("Typed key material metadata has invalid format: " + alias,
                        failure);
            }
            if (trusted != purposes.contains(KeyMaterialPurpose.TRUST_ANCHOR)
                    || secretKey != purposes.contains(KeyMaterialPurpose.CONFIGURATION_CIPHER)) {
                throw new GeneralSecurityException("Key material entry type does not match purpose: " + alias);
            }
            if (!purposes.containsAll(requested)) continue;
            Certificate[] chain = secretKey ? new Certificate[0] : keyStore.getCertificateChain(materialAlias(alias));
            if (trusted) {
                chain = new Certificate[]{keyStore.getCertificate(materialAlias(alias))};
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
            entries.add(new KeyMaterialInventoryEntry(alias, purposes, algorithm.name(), version,
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

    public synchronized void validateExisting(TrustedCertificateDescriptor descriptor)
            throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
        Addition addition = readAddition(descriptor.alias());
        if (addition == null || addition.deleted()) {
            throw new GeneralSecurityException("Key material alias not found: " + descriptor.alias());
        }
        validatePrepared(addition.entry(), descriptor);
    }

    synchronized KeyStore.Entry prepareKey(KeyMaterialDescriptor descriptor, int keySize)
            throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
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

    synchronized KeyStore.TrustedCertificateEntry prepareTrustedCertificate(TrustedCertificateDescriptor descriptor,
            X509Certificate certificate) throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
        if (certificate == null) throw new IllegalArgumentException("Trusted certificate must not be null");
        if (certificate.getBasicConstraints() < 0) {
            throw new GeneralSecurityException("Trusted certificate must be a certificate authority");
        }
        certificate.checkValidity();
        requireAlgorithm(descriptor, certificate.getPublicKey().getAlgorithm());
        return new KeyStore.TrustedCertificateEntry(certificate, descriptorAttributes(descriptor));
    }

    synchronized void validatePrepared(KeyStore.Entry entry, KeyMaterialDescriptor descriptor)
            throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
        if (descriptor.purposes().contains(KeyMaterialPurpose.CONFIGURATION_CIPHER)) {
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

    synchronized void validatePrepared(KeyStore.Entry entry, TrustedCertificateDescriptor descriptor)
            throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
        if (!(entry instanceof KeyStore.TrustedCertificateEntry trusted)) {
            throw new GeneralSecurityException("Key material entry type does not match purpose");
        }
        requireAlgorithm(descriptor, trusted.getTrustedCertificate().getPublicKey().getAlgorithm());
        validateMetadata(descriptor.alias(), attribute(entry.getAttributes(), DESCRIPTOR_ATTRIBUTE_OID),
                descriptor.purposes(), descriptor.algorithm(), descriptor.version());
    }

    synchronized Addition readAddition(String alias) throws GeneralSecurityException {
        requireOpen();
        requireAlias(alias);
        EntryVersion latest = findVersion(alias);
        if (latest == null) return null;
        return new Addition(latest.alias(), readEntry(keyStore, latest.physicalAlias()),
                latest.version(), latest.deleted());
    }

    private void append(KeyStore target, Addition addition, long kmsVersion) throws GeneralSecurityException {
        String alias = addition.alias().toLowerCase(Locale.ROOT);
        String physical = target.containsAlias(alias)
                ? "__orion_kms_" + HexFormat.of().formatHex(alias.getBytes(StandardCharsets.UTF_8))
                        + "_v" + addition.version()
                : alias;
        if (target.containsAlias(physical)) {
            throw new GeneralSecurityException("Key material version already exists: " + alias);
        }
        Set<KeyStore.Entry.Attribute> attributes = new HashSet<>();
        for (KeyStore.Entry.Attribute existing : addition.entry().getAttributes()) {
            if (!ADDITION_ATTRIBUTE_OID.equals(existing.getName())) attributes.add(existing);
        }
        String metadata = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(alias.getBytes(StandardCharsets.UTF_8))
                + ";" + addition.version() + ";" + kmsVersion + ";" + addition.deleted();
        attributes.add(new PKCS12Attribute(ADDITION_ATTRIBUTE_OID, metadata));
        KeyStore.Entry entry = switch (addition.entry()) {
            case KeyStore.PrivateKeyEntry key -> new KeyStore.PrivateKeyEntry(
                    key.getPrivateKey(), key.getCertificateChain(), attributes);
            case KeyStore.SecretKeyEntry secret -> new KeyStore.SecretKeyEntry(secret.getSecretKey(), attributes);
            case KeyStore.TrustedCertificateEntry trusted -> new KeyStore.TrustedCertificateEntry(
                    trusted.getTrustedCertificate(), attributes);
            default -> throw new GeneralSecurityException("Unknown key material entry type");
        };
        char[] password = options.password();
        try {
            KeyStore.ProtectionParameter protection = entry instanceof KeyStore.TrustedCertificateEntry
                    ? null : new KeyStore.PasswordProtection(password);
            target.setEntry(physical, entry, protection);
        } finally {
            clear(password);
        }
    }

    private List<String> physicalAliases() throws GeneralSecurityException {
        return physicalAliases(keyStore);
    }

    private static List<String> physicalAliases(KeyStore source) throws GeneralSecurityException {
        List<String> aliases = new ArrayList<>();
        source.aliases().asIterator().forEachRemaining(aliases::add);
        return aliases;
    }

    private List<String> availableAliases() throws GeneralSecurityException {
        List<String> aliases = new ArrayList<>();
        for (String physical : physicalAliases()) {
            String alias = entryVersion(physical).alias();
            if (!aliases.contains(alias) && !findVersion(alias).deleted()) aliases.add(alias);
        }
        aliases.sort(String::compareTo);
        return aliases;
    }

    private String materialAlias(String alias) throws GeneralSecurityException {
        EntryVersion latest = findVersion(alias);
        if (latest == null || latest.deleted()) {
            throw new GeneralSecurityException("Key material alias not found: " + alias);
        }
        return latest.physicalAlias();
    }

    private EntryVersion findVersion(String alias) throws GeneralSecurityException {
        EntryVersion latest = null;
        for (String physical : physicalAliases()) {
            EntryVersion entry = entryVersion(physical);
            if (entry.alias().equalsIgnoreCase(alias)
                    && (latest == null || entry.version() > latest.version())) latest = entry;
        }
        return latest;
    }

    private EntryVersion entryVersion(String physical) throws GeneralSecurityException {
        return entryVersion(keyStore, physical);
    }

    private static EntryVersion entryVersion(KeyStore source, String physical) throws GeneralSecurityException {
        String metadata = attribute(source.getAttributes(physical), ADDITION_ATTRIBUTE_OID);
        if (metadata == null) return new EntryVersion(physical, physical, 1, 0, false);
        try {
            String[] fields = metadata.split(";", -1);
            if (fields.length != 4 || (!fields[3].equals("true") && !fields[3].equals("false"))) {
                throw new IllegalArgumentException();
            }
            String alias = new String(Base64.getUrlDecoder().decode(fields[0]), StandardCharsets.UTF_8);
            long entryVersion = Long.parseLong(fields[1]);
            long kmsVersion = Long.parseLong(fields[2]);
            if (alias.isBlank() || entryVersion < 1 || kmsVersion < 1) throw new IllegalArgumentException();
            return new EntryVersion(physical, alias, entryVersion, kmsVersion, Boolean.parseBoolean(fields[3]));
        } catch (IllegalArgumentException failure) {
            throw new GeneralSecurityException("Invalid key material addition: " + physical, failure);
        }
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

    private record EntryVersion(String physicalAlias, String alias, long version, long kmsVersion, boolean deleted) {}

    synchronized long revision() {
        requireOpen();
        return revision;
    }

    synchronized KeyMaterialModification modify() {
        requireOpen();
        return new KeyMaterialModification(this, Math.incrementExact(revision));
    }

    void apply(List<Addition> additions, long nextVersion) throws IOException {
        Set<String> resources = new HashSet<>();
        synchronized (writes) {
            byte[] serialized;
            String expected;
            KeyStore publication;
            synchronized (this) {
                requireOpen();
                if (revision != nextVersion - 1) {
                    throw new KeyMaterialStoreConflictException("Key material changed before apply");
                }
                if (additions.isEmpty()) return;
                try {
                    publication = copyForPublication();
                    for (Addition addition : additions) append(publication, addition, nextVersion);
                    serialized = serialize(publication);
                    expected = version;
                } catch (GeneralSecurityException failure) {
                    close();
                    throw new IOException("Cannot prepare key material", failure);
                } catch (IOException | RuntimeException failure) {
                    close();
                    throw failure;
                }
            }
            try {
                String saved;
                try {
                    saved = store.write(serialized, expected);
                } finally {
                    Arrays.fill(serialized, (byte) 0);
                }
                synchronized (this) {
                    requireOpen();
                    if (revision < nextVersion) {
                        for (Addition addition : additions) {
                            append(keyStore, addition, nextVersion);
                            resources.add(addition.alias());
                        }
                        version = saved;
                        revision = nextVersion;
                    } else {
                        for (String physical : physicalAliases(publication)) {
                            if (!keyStore.containsAlias(physical)
                                    || !sameEntry(readEntry(keyStore, physical), readEntry(publication, physical))) {
                                throw new GeneralSecurityException("Reloaded material does not contain saved history");
                            }
                        }
                    }
                }
            } catch (GeneralSecurityException failure) {
                close();
                throw new IOException("Cannot save key material", failure);
            } catch (IOException | RuntimeException failure) {
                close();
                throw failure;
            }
        }
        if (!resources.isEmpty()) publishChanged(resources);
    }

    void prepareConfigurationCipher(KeyMaterialDescriptor descriptor) throws GeneralSecurityException {
        synchronized (writes) {
            if (containsAlias(descriptor.alias(), Set.of())) return;
            try {
                KeyMaterialModification modification = modify();
                try {
                    modification.generateKey(descriptor, 256);
                    modification.apply();
                } finally {
                    modification.discard();
                }
            } catch (IOException | GeneralSecurityException | RuntimeException failure) {
                close();
                throw new GeneralSecurityException("Cannot persist configuration cipher", failure);
            }
        }
    }

    void reload(String requestedRevision) throws IOException, GeneralSecurityException {
        Set<String> resources = reloadState(requestedRevision);
        if (!resources.isEmpty()) publishChanged(resources);
    }

    private synchronized Set<String> reloadState(String requestedRevision)
            throws IOException, GeneralSecurityException {
        requireOpen();
        Objects.requireNonNull(requestedRevision, "revision");
        if (Objects.equals(version, requestedRevision)) return Set.of();
        KeyMaterialSnapshot snapshot = store.read(requestedRevision)
                .orElseThrow(() -> new IOException("Key material is missing at " + requestedRevision));
        if (Objects.equals(version, snapshot.version())) return Set.of();
        KeyStore incoming = KeyStore.getInstance(options.type());
        byte[] bytes = snapshot.bytes();
        char[] password = options.password();
        try {
            incoming.load(new ByteArrayInputStream(bytes), password);
        } finally {
            Arrays.fill(bytes, (byte) 0);
            clear(password);
        }
        for (String physical : physicalAliases()) {
            if (!incoming.containsAlias(physical)
                    || !sameEntry(readEntry(keyStore, physical), readEntry(incoming, physical))) {
                throw new GeneralSecurityException("Key material history was replaced or removed: " + physical);
            }
        }
        Map<String, KeyStore.Entry> additions = new LinkedHashMap<>();
        Set<String> resources = new HashSet<>();
        Set<String> entryVersions = new HashSet<>();
        long nextRevision = revision;
        for (String physical : physicalAliases(incoming)) {
            EntryVersion entry = entryVersion(incoming, physical);
            if (!entryVersions.add(entry.alias() + ":" + entry.version())) {
                throw new GeneralSecurityException("Duplicate key material version: " + entry.alias());
            }
            if (keyStore.containsAlias(physical)) continue;
            EntryVersion previous = findVersion(entry.alias());
            if (entry.kmsVersion() <= revision
                    || (previous != null && entry.version() <= previous.version())) {
                throw new GeneralSecurityException("Key material revision does not extend active history");
            }
            additions.put(physical, readEntry(incoming, physical));
            resources.add(entry.alias());
            nextRevision = Math.max(nextRevision, entry.kmsVersion());
        }
        password = options.password();
        try {
            for (Map.Entry<String, KeyStore.Entry> addition : additions.entrySet()) {
                KeyStore.ProtectionParameter protection = addition.getValue() instanceof KeyStore.TrustedCertificateEntry
                        ? null : new KeyStore.PasswordProtection(password);
                keyStore.setEntry(addition.getKey(), addition.getValue(), protection);
            }
        } catch (GeneralSecurityException failure) {
            close();
            throw failure;
        } finally {
            clear(password);
        }
        version = snapshot.version();
        revision = nextRevision;
        return Set.copyOf(resources);
    }

    private KeyStore.Entry readEntry(KeyStore source, String physical) throws GeneralSecurityException {
        char[] password = options.password();
        try {
            boolean trusted = source.entryInstanceOf(physical, KeyStore.TrustedCertificateEntry.class);
            KeyStore.ProtectionParameter protection = trusted ? null : new KeyStore.PasswordProtection(password);
            return source.getEntry(physical, protection);
        } finally {
            clear(password);
        }
    }

    private static boolean sameEntry(KeyStore.Entry first, KeyStore.Entry second) {
        if (!stableAttributes(first).equals(stableAttributes(second))) return false;
        return switch (first) {
            case KeyStore.PrivateKeyEntry key -> second instanceof KeyStore.PrivateKeyEntry other
                    && sameKey(key.getPrivateKey(), other.getPrivateKey())
                    && Arrays.equals(key.getCertificateChain(), other.getCertificateChain());
            case KeyStore.SecretKeyEntry key -> second instanceof KeyStore.SecretKeyEntry other
                    && sameKey(key.getSecretKey(), other.getSecretKey());
            case KeyStore.TrustedCertificateEntry trusted -> second instanceof KeyStore.TrustedCertificateEntry other
                    && trusted.getTrustedCertificate().equals(other.getTrustedCertificate());
            default -> false;
        };
    }

    private static Set<KeyStore.Entry.Attribute> stableAttributes(KeyStore.Entry entry) {
        Set<KeyStore.Entry.Attribute> attributes = new HashSet<>();
        for (KeyStore.Entry.Attribute attribute : entry.getAttributes()) {
            if (!LOCAL_KEY_ID_ATTRIBUTE_OID.equals(attribute.getName())) attributes.add(attribute);
        }
        return attributes;
    }

    private static boolean sameKey(Key first, Key second) {
        byte[] firstEncoded = first.getEncoded();
        byte[] secondEncoded = second.getEncoded();
        try {
            return firstEncoded != null && secondEncoded != null
                    && MessageDigest.isEqual(firstEncoded, secondEncoded);
        } finally {
            if (firstEncoded != null) Arrays.fill(firstEncoded, (byte) 0);
            if (secondEncoded != null) Arrays.fill(secondEncoded, (byte) 0);
        }
    }

    private void publishChanged(Set<String> resources) {
        try {
            eventPublisher.publishIfRunning(new KeyMaterialChanged(Set.copyOf(resources)));
        } catch (RuntimeException failure) {
            System.getLogger(KeyMaterialService.class.getName()).log(System.Logger.Level.WARNING,
                    "Could not publish key material change event", failure);
        }
    }

    private KeyStore copyForPublication() throws IOException, GeneralSecurityException {
        byte[] serialized = serialize(keyStore);
        char[] password = options.password();
        try {
            KeyStore publication = KeyStore.getInstance(options.type());
            publication.load(new ByteArrayInputStream(serialized), password);
            return publication;
        } finally {
            Arrays.fill(serialized, (byte) 0);
            clear(password);
        }
    }

    private byte[] serialize(KeyStore source) throws IOException, GeneralSecurityException {
        char[] password = options.password();
        try (SensitiveByteArrayOutputStream output = new SensitiveByteArrayOutputStream()) {
            source.store(output, password);
            return output.toByteArray();
        } finally {
            clear(password);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            options.close();
        } finally {
            store.close();
        }
    }

    private static void requireAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("Key material alias must not be empty");
        }
    }

    synchronized SecretKey secretKey(String alias) throws GeneralSecurityException {
        requireOpen();
        requireAlias(alias);
        char[] password = options.password();
        try {
            if (!(keyStore.getKey(materialAlias(alias), password) instanceof SecretKey secretKey)) {
                throw new GeneralSecurityException("Secret key alias not found: " + alias);
            }
            return secretKey;
        } finally {
            clear(password);
        }
    }

    private String descriptorMetadata(String alias) throws GeneralSecurityException {
        String metadata = descriptorMetadataIfPresent(alias);
        if (metadata != null) {
            return metadata;
        }
        throw new GeneralSecurityException("Typed key material metadata is missing for alias: " + alias);
    }

    private String descriptorMetadataIfPresent(String alias) throws GeneralSecurityException {
        return attribute(keyStore.getAttributes(materialAlias(alias)), DESCRIPTOR_ATTRIBUTE_OID);
    }

    private static Set<KeyStore.Entry.Attribute> descriptorAttributes(KeyMaterialDescriptor descriptor) {
        return descriptorAttributes(
                descriptor.purposes(), descriptor.algorithm(), descriptor.version());
    }

    private static Set<KeyStore.Entry.Attribute> descriptorAttributes(
            TrustedCertificateDescriptor descriptor) {
        return descriptorAttributes(
                descriptor.purposes(), descriptor.algorithm(), descriptor.version());
    }

    private static Set<KeyStore.Entry.Attribute> descriptorAttributes(
            Set<KeyMaterialPurpose> purposes,
            KeyMaterialAlgorithm algorithm,
            long version) {
        String value = formatPurposes(purposes)
                + ";" + algorithm.name()
                + ";" + version;
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

    private static void requireAlgorithm(TrustedCertificateDescriptor descriptor, String actual)
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

    private static void requireDescriptor(TrustedCertificateDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("Trusted certificate descriptor must not be null");
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

    private static void clear(char[] value) {
        Arrays.fill(value, '\0');
    }

    synchronized void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Key material service is closed");
        }
    }

    private static final class SensitiveByteArrayOutputStream extends ByteArrayOutputStream {
        @Override
        public void close() throws IOException {
            Arrays.fill(buf, (byte) 0);
            super.close();
        }
    }
}
