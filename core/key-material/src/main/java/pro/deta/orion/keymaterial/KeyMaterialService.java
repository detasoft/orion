package pro.deta.orion.keymaterial;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @AiRule All public methods in this class must be synchronized.
 */
public class KeyMaterialService implements AutoCloseable {
    private final KeyMaterialContentStore store;
    private final KeyMaterialOptions options;
    private final KeyMaterialStorageCertificateFactory storageCertificateFactory;
    private final KeyStore keyStore;
    private final Map<String, KeyMaterialSigningKeyConfig> signingKeys;
    private String version;
    private boolean closed;

    private KeyMaterialService(
            KeyMaterialContentStore store,
            KeyMaterialOptions options,
            KeyMaterialStorageCertificateFactory storageCertificateFactory,
            KeyStore keyStore,
            String version,
            Map<String, KeyMaterialSigningKeyConfig> signingKeys) {
        this.store = store;
        this.options = options;
        this.storageCertificateFactory = storageCertificateFactory;
        this.keyStore = keyStore;
        this.version = version;
        this.signingKeys = new LinkedHashMap<>(signingKeys);
    }

    public static synchronized KeyMaterialService open(
            KeyMaterialContentStore store,
            KeyMaterialOptions options)
            throws IOException, GeneralSecurityException {
        return open(store, options, new KeyMaterialStorageCertificateFactory(), Map.of());
    }

    public static synchronized KeyMaterialService open(
            KeyMaterialContentStore store,
            KeyMaterialOptions options,
            Map<String, KeyMaterialSigningKeyConfig> signingKeys)
            throws IOException, GeneralSecurityException {
        return open(store, options, new KeyMaterialStorageCertificateFactory(), signingKeys);
    }

    public static synchronized KeyMaterialService open(
            KeyMaterialContentStore store,
            KeyMaterialOptions options,
            KeyMaterialStorageCertificateFactory storageCertificateFactory)
            throws IOException, GeneralSecurityException {
        return open(store, options, storageCertificateFactory, Map.of());
    }

    public static synchronized KeyMaterialService open(
            KeyMaterialContentStore store,
            KeyMaterialOptions options,
            KeyMaterialStorageCertificateFactory storageCertificateFactory,
            Map<String, KeyMaterialSigningKeyConfig> signingKeys)
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
        Map<String, KeyMaterialSigningKeyConfig> signingKeysCopy = copySigningKeys(signingKeys);
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
                            snapshot.get().version(),
                            signingKeysCopy);
                }
                if (!ownedOptions.createIfMissing()) {
                    throw new KeyMaterialStoreNotFoundException("Key material store does not exist");
                }
                keyStore.load(null, password);
                return new KeyMaterialService(
                        store,
                        ownedOptions,
                        storageCertificateFactory,
                        keyStore,
                        null,
                        signingKeysCopy);
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
        return keyStore.containsAlias(alias);
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
            PrivateKey privateKey = (PrivateKey) keyStore.getKey(alias, password);
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
        Certificate[] chain = keyStore.getCertificateChain(alias);
        if (chain == null) {
            throw new GeneralSecurityException("Certificate chain alias not found: " + alias);
        }
        return Arrays.copyOf(chain, chain.length);
    }

    public synchronized X509Certificate getTrustedCertificate(String alias) throws GeneralSecurityException {
        requireOpen();
        requireAlias(alias);
        if (!keyStore.entryInstanceOf(alias, KeyStore.TrustedCertificateEntry.class)) {
            throw new GeneralSecurityException("Trusted certificate alias not found: " + alias);
        }
        Certificate certificate = keyStore.getCertificate(alias);
        if (!(certificate instanceof X509Certificate x509Certificate)) {
            throw new GeneralSecurityException("Trusted certificate is not X.509: " + alias);
        }
        return x509Certificate;
    }

    public synchronized KeyPair getActiveSigningKey(String purpose) throws GeneralSecurityException {
        requireOpen();
        return getKeyPair(signingKeyConfig(purpose).activeAlias());
    }

    public synchronized List<KeyPair> getVerificationKeys(String purpose) throws GeneralSecurityException {
        requireOpen();
        List<KeyPair> keys = new ArrayList<>();
        for (String alias : signingKeyConfig(purpose).verificationAliasesIncludingActive()) {
            keys.add(getKeyPair(alias));
        }
        return List.copyOf(keys);
    }

    public synchronized void setPrivateKey(String alias, KeyPair keyPair, Certificate[] certificateChain)
            throws GeneralSecurityException {
        requireOpen();
        requireAlias(alias);
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
        char[] password = options.password();
        try {
            keyStore.setKeyEntry(alias, keyPair.getPrivate(), password, certificateChain);
        } finally {
            clear(password);
        }
    }

    public synchronized X509Certificate setPrivateKeyWithStorageCertificate(
            String alias,
            String purpose,
            KeyPair keyPair)
            throws GeneralSecurityException {
        requireOpen();
        X509Certificate certificate = storageCertificateFactory.create(alias, purpose, keyPair);
        setPrivateKey(alias, keyPair, new Certificate[]{certificate});
        return certificate;
    }

    public synchronized void setTrustedCertificate(
            String alias,
            Certificate certificate) throws GeneralSecurityException {
        requireOpen();
        requireAlias(alias);
        if (certificate == null) {
            throw new IllegalArgumentException("Trusted certificate must not be null");
        }
        keyStore.setCertificateEntry(alias, certificate);
    }

    public synchronized KeyPair generateKeyIfMissing(
            String alias,
            KeyMaterialKeySpec spec) throws GeneralSecurityException {
        requireOpen();
        requireAlias(alias);
        if (spec == null) {
            throw new IllegalArgumentException("Key spec must not be null");
        }
        if (keyStore.containsAlias(alias)) {
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

    public synchronized KeyMaterialSigningKeyConfig rotate(
            String purpose,
            String newAlias) throws GeneralSecurityException {
        requireOpen();
        requirePurpose(purpose);
        requireAlias(newAlias);
        if (!keyStore.entryInstanceOf(newAlias, KeyStore.PrivateKeyEntry.class)) {
            throw new GeneralSecurityException("Signing key alias not found: " + newAlias);
        }
        KeyMaterialSigningKeyConfig rotated = signingKeyConfig(purpose).rotateTo(newAlias);
        signingKeys.put(purpose, rotated);
        return rotated;
    }

    public synchronized String save() throws IOException, GeneralSecurityException {
        requireOpen();
        char[] password = options.password();
        try (SensitiveByteArrayOutputStream output = new SensitiveByteArrayOutputStream()) {
            keyStore.store(output, password);
            byte[] serialized = output.toByteArray();
            try {
                version = store.write(serialized, version);
            } finally {
                Arrays.fill(serialized, (byte) 0);
            }
        } finally {
            clear(password);
        }
        return version;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        options.close();
        signingKeys.clear();
        closed = true;
    }

    private static Map<String, KeyMaterialSigningKeyConfig> copySigningKeys(
            Map<String, KeyMaterialSigningKeyConfig> signingKeys) {
        if (signingKeys == null) {
            return Map.of();
        }
        Map<String, KeyMaterialSigningKeyConfig> copy = new LinkedHashMap<>();
        for (Map.Entry<String, KeyMaterialSigningKeyConfig> entry : signingKeys.entrySet()) {
            requirePurpose(entry.getKey());
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("Signing key config must not be null: " + entry.getKey());
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        return copy;
    }

    private KeyMaterialSigningKeyConfig signingKeyConfig(String purpose) throws GeneralSecurityException {
        requirePurpose(purpose);
        KeyMaterialSigningKeyConfig config = signingKeys.get(purpose);
        if (config == null) {
            throw new GeneralSecurityException("Signing key purpose is not configured: " + purpose);
        }
        return config;
    }

    private static void requireAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("Key material alias must not be empty");
        }
    }

    private static void requirePurpose(String purpose) {
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException("Key material purpose must not be empty");
        }
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

    private void requireOpen() {
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
