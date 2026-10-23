package pro.deta.orion.keymaterial;

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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * @AiRule All public methods in this class must be synchronized.
 */
public class KeyMaterialService implements AutoCloseable {
    private static final String DESCRIPTOR_ATTRIBUTE_OID =
            "2.25.260349814076539099691640886169327825428";
    private static final String ADDITION_ATTRIBUTE_OID =
            "2.25.260349814076539099691640886169327826429";
    private final KeyMaterialContentStore store;
    private final KeyMaterialOptions options;
    private final KeyMaterialStorageCertificateFactory storageCertificateFactory;
    private final KeyStore keyStore;
    private String version;
    private long revision;
    private boolean closed;

    private KeyMaterialService(
            KeyMaterialContentStore store,
            KeyMaterialOptions options,
            KeyMaterialStorageCertificateFactory storageCertificateFactory,
            KeyStore keyStore,
            String version) throws GeneralSecurityException {
        this.store = store;
        this.options = options;
        this.storageCertificateFactory = storageCertificateFactory;
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
        if (store == null) {
            throw new IllegalArgumentException("Key material store must not be null");
        }
        if (options == null) {
            throw new IllegalArgumentException("Key material options must not be null");
        }
        if (storageCertificateFactory == null) {
            throw new IllegalArgumentException("Storage certificate factory must not be null");
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
                            keyStore,
                            snapshot.get().version());
                }
                keyStore.load(null, password);
                return new KeyMaterialService(
                        store,
                        ownedOptions,
                        storageCertificateFactory,
                        keyStore,
                        null);
            } finally {
                clear(password);
            }
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            ownedOptions.close();
            throw e;
        }
    }

    public synchronized boolean containsAlias(String alias) throws GeneralSecurityException {
        requireOpen();
        requireAlias(alias);
        EntryVersion latest = findVersion(alias);
        return latest != null && !latest.deleted();
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
        String alias = descriptor.alias().value();
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

    public synchronized void setPrivateKey(
            KeyMaterialDescriptor descriptor,
            KeyPair keyPair,
            List<? extends Certificate> certificateChain)
            throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
        if (descriptor.purpose() == KeyMaterialPurpose.CONFIGURATION_CIPHER
                || descriptor.purpose() == KeyMaterialPurpose.TRUST_ANCHOR) {
            throw new IllegalArgumentException("Private key descriptor must have a private key purpose");
        }
        Certificate[] certificates = requireCertificateChain(certificateChain);
        requireMatchingLeaf(descriptor.alias().value(), keyPair, certificates);
        setTypedPrivateKey(descriptor, keyPair, certificates);
    }

    private void setUntypedPrivateKey(String alias, KeyPair keyPair, Certificate[] certificateChain)
            throws GeneralSecurityException {
        requireAlias(alias);
        requireMatchingLeaf(alias, keyPair, certificateChain);
        appendLegacy(alias, new KeyStore.PrivateKeyEntry(keyPair.getPrivate(), certificateChain));
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

    private X509Certificate setPrivateKeyWithStorageCertificate(
            String alias,
            String purpose,
            KeyPair keyPair)
            throws GeneralSecurityException {
        requireOpen();
        X509Certificate certificate = storageCertificateFactory.create(alias, purpose, keyPair);
        setUntypedPrivateKey(alias, keyPair, new Certificate[]{certificate});
        return certificate;
    }

    public synchronized void setTrustedCertificate(
            TrustedCertificateDescriptor descriptor,
            X509Certificate certificate) throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
        appendLegacy(descriptor.alias().value(), prepareTrustedCertificate(descriptor, certificate));
    }

    public synchronized KeyPair generateKeyIfMissing(
            String alias,
            KeyMaterialKeySpec spec) throws GeneralSecurityException {
        requireOpen();
        requireAlias(alias);
        if (spec == null) {
            throw new IllegalArgumentException("Key spec must not be null");
        }
        if (containsAlias(alias)) {
            return getKeyPair(alias);
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance(spec.algorithm());
        if (spec.keySize() > 0) {
            generator.initialize(spec.keySize());
        }
        KeyPair keyPair = generator.generateKeyPair();
        setPrivateKeyWithStorageCertificate(alias, spec.purpose(), keyPair);
        return keyPair;
    }

    public synchronized KeyPair generateKeyIfMissing(
            KeyMaterialDescriptor descriptor,
            int keySize) throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
        if (descriptor.algorithm() == KeyMaterialAlgorithm.AES) {
            throw new IllegalArgumentException("Private key material cannot use AES");
        }
        if (keySize < 0) {
            throw new IllegalArgumentException("Key size must not be negative");
        }
        if (containsAlias(descriptor.alias().value())) {
            validateExisting(descriptor);
            return getKeyPair(descriptor.alias().value());
        }

        KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) prepareKey(descriptor, keySize);
        appendLegacy(descriptor.alias().value(), entry);
        return new KeyPair(entry.getCertificate().getPublicKey(), entry.getPrivateKey());
    }

    public synchronized void generateSecretKeyIfMissing(
            KeyMaterialDescriptor descriptor,
            int keySize) throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
        if (descriptor.purpose() != KeyMaterialPurpose.CONFIGURATION_CIPHER
                || descriptor.algorithm() != KeyMaterialAlgorithm.AES) {
            throw new IllegalArgumentException("Secret key material must be an AES configuration cipher");
        }
        if (keySize < 1) {
            throw new IllegalArgumentException("Secret key size must be positive");
        }
        if (containsAlias(descriptor.alias().value())) {
            validateExisting(descriptor);
            return;
        }
        appendLegacy(descriptor.alias().value(), prepareKey(descriptor, keySize));
    }

    public synchronized void validateExisting(KeyMaterialDescriptor descriptor)
            throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
        Addition addition = readAddition(descriptor.alias().value());
        if (addition == null || addition.deleted()) {
            throw new GeneralSecurityException("Key material alias not found: " + descriptor.alias().value());
        }
        validatePrepared(addition.entry(), descriptor);
    }

    public synchronized List<KeyMaterialDescriptor> privateKeyDescriptors(
            KeyMaterialPurpose purpose) throws GeneralSecurityException {
        requireOpen();
        if (purpose == null) {
            throw new IllegalArgumentException("Key material purpose must not be null");
        }
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
            if (!purpose.name().equals(fields[0])) {
                continue;
            }
            KeyMaterialDescriptor descriptor;
            try {
                descriptor = new KeyMaterialDescriptor(
                        new KeyMaterialAlias(alias),
                        purpose,
                        KeyMaterialAlgorithm.valueOf(fields[1]),
                        new KeyMaterialVersion(Long.parseLong(fields[2])));
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

    public synchronized List<KeyMaterialInventoryEntry> inventory() throws GeneralSecurityException {
        requireOpen();
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
            KeyMaterialPurpose purpose;
            KeyMaterialAlgorithm algorithm;
            long version;
            try {
                purpose = KeyMaterialPurpose.valueOf(fields[0]);
                algorithm = KeyMaterialAlgorithm.valueOf(fields[1]);
                version = Long.parseLong(fields[2]);
            } catch (IllegalArgumentException failure) {
                throw new GeneralSecurityException("Typed key material metadata has invalid format: " + alias,
                        failure);
            }
            if (trusted != (purpose == KeyMaterialPurpose.TRUST_ANCHOR)
                    || secretKey != (purpose == KeyMaterialPurpose.CONFIGURATION_CIPHER)) {
                throw new GeneralSecurityException("Key material entry type does not match purpose: " + alias);
            }
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
            entries.add(new KeyMaterialInventoryEntry(alias, purpose.name(), algorithm.name(), version,
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
        Addition addition = readAddition(descriptor.alias().value());
        if (addition == null || addition.deleted()) {
            throw new GeneralSecurityException("Key material alias not found: " + descriptor.alias().value());
        }
        validatePrepared(addition.entry(), descriptor);
    }

    synchronized AcmeKeyMaterial acquireAcmeKeys(
            KeyMaterialDescriptor account,
            int accountKeySize,
            KeyMaterialDescriptor identity,
            int domainKeySize) throws IOException, GeneralSecurityException {
        requireOpen();
        boolean saveRequired = !containsAlias(account.alias().value())
                || !containsAlias(identity.alias().value());
        KeyPair accountKeyPair = generateKeyIfMissing(account, accountKeySize);
        KeyPair domainKeyPair = generateKeyIfMissing(identity, domainKeySize);
        if (saveRequired) {
            saveAcmeMaterial();
        }
        return new AcmeKeyMaterial(accountKeyPair, domainKeyPair);
    }

    synchronized void installAcmeCertificateChain(
            KeyMaterialDescriptor identity,
            List<X509Certificate> certificateChain,
            Optional<TrustedCertificateDescriptor> issuerDescriptor,
            Optional<X509Certificate> issuerCertificate) throws IOException, GeneralSecurityException {
        requireOpen();
        KeyPair domainKeyPair = getKeyPair(identity.alias().value());
        setPrivateKey(identity, domainKeyPair, certificateChain);
        if (issuerDescriptor.isPresent()) {
            setTrustedCertificate(issuerDescriptor.orElseThrow(), issuerCertificate.orElseThrow());
        }
        saveAcmeMaterial();
    }

    private void saveAcmeMaterial() throws IOException, GeneralSecurityException {
        try {
            save();
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            close();
            throw failure;
        }
    }

    public synchronized String save() throws IOException, GeneralSecurityException {
        requireOpen();
        byte[] serialized = serialize(keyStore);
        try {
            version = store.write(serialized, version);
            return version;
        } finally {
            Arrays.fill(serialized, (byte) 0);
        }
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
                descriptor.alias().value(), descriptor.purpose().storageName(), pair);
        return prepareCertificateChain(descriptor, pair, List.of(certificate));
    }

    synchronized KeyStore.PrivateKeyEntry prepareCertificateChain(KeyMaterialDescriptor descriptor, KeyPair pair,
            List<? extends Certificate> chain) throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
        Certificate[] certificates = requireCertificateChain(chain);
        requireMatchingLeaf(descriptor.alias().value(), pair, certificates);
        return new KeyStore.PrivateKeyEntry(pair.getPrivate(), certificates, descriptorAttributes(descriptor));
    }

    synchronized KeyStore.SecretKeyEntry prepareSecretKey(KeyMaterialDescriptor descriptor, SecretKey key)
            throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
        if (descriptor.purpose() != KeyMaterialPurpose.CONFIGURATION_CIPHER) {
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
        if (descriptor.purpose() == KeyMaterialPurpose.CONFIGURATION_CIPHER) {
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
        validateMetadata(descriptor.alias().value(), attribute(entry.getAttributes(), DESCRIPTOR_ATTRIBUTE_OID),
                descriptor.purpose(), descriptor.algorithm(), descriptor.version());
    }

    synchronized void validatePrepared(KeyStore.Entry entry, TrustedCertificateDescriptor descriptor)
            throws GeneralSecurityException {
        requireOpen();
        requireDescriptor(descriptor);
        if (!(entry instanceof KeyStore.TrustedCertificateEntry trusted)) {
            throw new GeneralSecurityException("Key material entry type does not match purpose");
        }
        requireAlgorithm(descriptor, trusted.getTrustedCertificate().getPublicKey().getAlgorithm());
        validateMetadata(descriptor.alias().value(), attribute(entry.getAttributes(), DESCRIPTOR_ATTRIBUTE_OID),
                descriptor.purpose(), descriptor.algorithm(), descriptor.version());
    }

    synchronized Addition readAddition(String alias) throws GeneralSecurityException {
        requireOpen();
        requireAlias(alias);
        EntryVersion latest = findVersion(alias);
        if (latest == null) return null;
        char[] password = options.password();
        try {
            KeyStore.ProtectionParameter protection =
                    keyStore.entryInstanceOf(latest.physicalAlias(), KeyStore.TrustedCertificateEntry.class)
                            ? null : new KeyStore.PasswordProtection(password);
            return new Addition(latest.alias(), keyStore.getEntry(latest.physicalAlias(), protection),
                    latest.version(), latest.deleted());
        } finally {
            clear(password);
        }
    }

    private void appendLegacy(String alias, KeyStore.Entry entry) throws GeneralSecurityException {
        requireOpen();
        EntryVersion previous = findVersion(alias);
        if (previous != null && previous.deleted()) {
            throw new GeneralSecurityException("Key material is deleted: " + alias);
        }
        long nextEntryVersion = previous == null ? 1 : Math.incrementExact(previous.version());
        long nextRevision = Math.incrementExact(revision);
        append(keyStore, new Addition(alias, entry, nextEntryVersion, false), nextRevision);
        revision = nextRevision;
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
        List<String> aliases = new ArrayList<>();
        keyStore.aliases().asIterator().forEachRemaining(aliases::add);
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
        String metadata = attribute(keyStore.getAttributes(physical), ADDITION_ATTRIBUTE_OID);
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

    private static void validateMetadata(String alias, String metadata, KeyMaterialPurpose purpose,
            KeyMaterialAlgorithm algorithm, KeyMaterialVersion version)
            throws GeneralSecurityException {
        if (metadata == null) throw new GeneralSecurityException("Typed key material metadata is missing: " + alias);
        String[] fields = metadata.split(";", -1);
        if (fields.length != 3) throw metadataFailure(alias, "format");
        requireMetadataField(alias, "purpose", purpose.name(), fields[0]);
        requireMetadataField(alias, "algorithm", algorithm.name(), fields[1]);
        requireMetadataField(alias, "version", Long.toString(version.value()), fields[2]);
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

    synchronized void apply(List<Addition> additions, long nextVersion) throws IOException {
        requireOpen();
        if (revision != nextVersion - 1) {
            throw new KeyMaterialStoreConflictException("Key material changed before apply");
        }
        if (additions.isEmpty()) return;
        try {
            KeyStore publication = copyForPublication();
            for (Addition addition : additions) append(publication, addition, nextVersion);
            byte[] serialized = serialize(publication);
            try {
                version = store.write(serialized, version);
            } finally {
                Arrays.fill(serialized, (byte) 0);
            }
            for (Addition addition : additions) append(keyStore, addition, nextVersion);
            revision = nextVersion;
        } catch (GeneralSecurityException failure) {
            close();
            throw new IOException("Cannot save key material", failure);
        } catch (IOException | RuntimeException failure) {
            close();
            throw failure;
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
        options.close();
        closed = true;
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

    private void setTypedPrivateKey(
            KeyMaterialDescriptor descriptor,
            KeyPair keyPair,
            Certificate[] certificateChain) throws GeneralSecurityException {
        appendLegacy(descriptor.alias().value(),
                new KeyStore.PrivateKeyEntry(keyPair.getPrivate(), certificateChain, descriptorAttributes(descriptor)));
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
                descriptor.purpose(), descriptor.algorithm(), descriptor.version());
    }

    private static Set<KeyStore.Entry.Attribute> descriptorAttributes(
            TrustedCertificateDescriptor descriptor) {
        return descriptorAttributes(
                descriptor.purpose(), descriptor.algorithm(), descriptor.version());
    }

    private static Set<KeyStore.Entry.Attribute> descriptorAttributes(
            KeyMaterialPurpose purpose,
            KeyMaterialAlgorithm algorithm,
            KeyMaterialVersion version) {
        String value = purpose.name()
                + ";" + algorithm.name()
                + ";" + version.value();
        return Set.of(new PKCS12Attribute(DESCRIPTOR_ATTRIBUTE_OID, value));
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
                    "Key material algorithm does not match alias " + descriptor.alias().value()
                            + ": expected " + descriptor.algorithm().keyAlgorithm() + ", found " + actual);
        }
    }

    private static void requireAlgorithm(TrustedCertificateDescriptor descriptor, String actual)
            throws GeneralSecurityException {
        if (!descriptor.algorithm().acceptsKeyAlgorithm(actual)) {
            throw new GeneralSecurityException(
                    "Key material algorithm does not match alias " + descriptor.alias().value()
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
