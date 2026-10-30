package pro.deta.orion.keymaterial;

import pro.deta.orion.storage.BlobStorageConflictException;

import java.util.Set;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import java.io.IOException;
import java.io.CharArrayReader;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.security.cert.X509Certificate;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Owns runtime key material, reads committed entries and prepares isolated modifications.
 * The configuration wrapping key is persisted on first seal;
 * a failed material save invalidates this owner so a caller must reopen the durable state before retrying.
 */
public class OrionKeyMaterial implements AutoCloseable {
    private static final int DATA_KEY_BITS = 256;
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_NONCE_BYTES = 12;

    private final SecureRandom secureRandom = new SecureRandom();
    private final KeyMaterialService owner;

    private OrionKeyMaterial(KeyMaterialService owner) {
        this.owner = owner;
    }

    public static OrionKeyMaterial open(KeyMaterialRepository store) {
        return new OrionKeyMaterial(new KeyMaterialService(store));
    }

    protected OrionKeyMaterial() {
        this(null);
    }

    public static OrionKeyMaterial unavailable() {
        return new OrionKeyMaterial();
    }

    public boolean hasDurableSnapshot() {
        requireAvailable();
        return owner.hasDurableSnapshot();
    }

    public void reload(String revision) throws IOException, GeneralSecurityException {
        requireAvailable();
        owner.reload(revision);
    }

    public String revision() {
        requireAvailable();
        return owner.revision();
    }

    public KeyMaterialModification modify(String expectedRevision) throws BlobStorageConflictException {
        requireAvailable();
        return owner.modify(expectedRevision);
    }

    public void withModification(String expectedRevision, KeyMaterialModification.Operation operation)
            throws IOException, GeneralSecurityException {
        Objects.requireNonNull(operation, "operation");
        KeyMaterialModification modification = modify(expectedRevision);
        try {
            operation.accept(modification);
        } finally {
            modification.discard();
        }
    }

    public boolean contains(KeyMaterialId descriptor) throws GeneralSecurityException {
        Objects.requireNonNull(descriptor, "descriptor");
        requireAvailable();
        synchronized (owner) {
            if (!owner.containsAlias(descriptor.alias(), Set.of())) return false;
            owner.read(descriptor);
            return true;
        }
    }

    public boolean contains(String alias, Set<KeyMaterialPurpose> requiredPurposes) throws GeneralSecurityException {
        requireAvailable();
        Objects.requireNonNull(alias, "alias");
        return owner.containsAlias(alias, requiredPurposes);
    }

    public KeyMaterialEntry read(KeyMaterialId id) throws GeneralSecurityException {
        requireAvailable();
        return owner.read(id);
    }

    public byte[] sign(KeyMaterialId descriptor, byte[] payload) throws GeneralSecurityException {
        requireAvailable();
        synchronized (owner) {
            KeyMaterialEntry entry = requireSigningMaterial(descriptor);
            requireBytes(payload, "Payload");
            Signature signer = Signature.getInstance(entry.algorithm().requireSignatureAlgorithm());
            signer.initSign(read(descriptor).keyPair().getPrivate());
            signer.update(payload);
            return signer.sign();
        }
    }

    public boolean verify(KeyMaterialId descriptor, byte[] payload, byte[] signature)
            throws GeneralSecurityException {
        requireAvailable();
        synchronized (owner) {
            KeyMaterialEntry entry = requireSigningMaterial(descriptor);
            requireBytes(payload, "Payload");
            requireBytes(signature, "Signature");
            Signature verifier = Signature.getInstance(entry.algorithm().requireSignatureAlgorithm());
            verifier.initVerify(read(descriptor).keyPair().getPublic());
            verifier.update(payload);
            return verifier.verify(signature);
        }
    }

    private KeyMaterialEntry requireSigningMaterial(KeyMaterialId id) throws GeneralSecurityException {
        KeyMaterialEntry entry = read(id);
        if (entry.purposes().contains(KeyMaterialPurpose.TRUST_ANCHOR)
                || (!entry.purposes().contains(KeyMaterialPurpose.SERVER_SIGNING)
                        && !entry.purposes().contains(KeyMaterialPurpose.CERTIFICATE_AUTHORITY))) {
            throw new IllegalArgumentException("Signatures require server signing or CA material");
        }
        return entry;
    }

    public ConfigurationSecretEnvelope seal(KeyMaterialId descriptor, byte[] plaintext,
            ConfigurationSecretContext context) throws GeneralSecurityException {
        requireAvailable();
        if (plaintext == null || plaintext.length == 0 || context == null) {
            throw new IllegalArgumentException("Configuration secret and context must not be empty");
        }
        owner.prepareConfigurationCipher(descriptor);
        requireCipher(descriptor);
        synchronized (owner) {
            return sealConfigurationSecret(descriptor, plaintext, context);
        }
    }

    public byte[] open(KeyMaterialId descriptor, ConfigurationSecretEnvelope envelope,
            ConfigurationSecretContext context) throws GeneralSecurityException {
        requireAvailable();
        synchronized (owner) {
            requireCipher(descriptor);
            return openConfigurationSecret(descriptor, envelope, context);
        }
    }

    private void requireCipher(KeyMaterialId id) throws GeneralSecurityException {
        KeyMaterialEntry entry = read(id);
        if (!entry.purposes().contains(KeyMaterialPurpose.CONFIGURATION_CIPHER)) {
            throw new IllegalArgumentException("Encryption requires configuration cipher material");
        }
    }

    public SSLContext createTlsContext(TlsMaterialConfiguration configuration) throws GeneralSecurityException {
        requireAvailable();
        synchronized (owner) {
            CertificateIdentity identity = certificateIdentity(configuration);
            List<X509Certificate> chain = identity.certificateChain();
            if (chain.size() == 1 && CertificateIdentity.isStorageCertificate(chain.getFirst())) {
                throw new GeneralSecurityException("TLS identity must contain an issued certificate chain");
            }
            CertificateIdentity.validateCertificateChain(
                    chain, read(configuration.identity()).publicKey(), identity.issuerTrustAnchor());
            return createTlsContext(configuration.identity(), chain, configuration.clientTrustAnchors());
        }
    }

    public List<KeyPair> sshHostKeyPairs(List<String> references) throws GeneralSecurityException {
        requireAvailable();
        if (references == null) throw new IllegalArgumentException("SSH host key references must not be null");
        synchronized (owner) {
            List<KeyMaterialId> available = new ArrayList<>();
            for (KeyMaterialId id : inventory()) {
                KeyMaterialEntry entry = read(id);
                if (entry.content() instanceof KeyStore.PrivateKeyEntry
                        && entry.purposes().contains(KeyMaterialPurpose.SSH_HOST)) {
                    available.add(entry.id());
                }
            }
            List<KeyMaterialId> selected = references.isEmpty()
                    ? available : selectSshHostKeys(available, references);
            List<KeyPair> keys = new ArrayList<>();
            for (KeyMaterialId descriptor : selected) keys.add(read(descriptor).keyPair());
            return List.copyOf(keys);
        }
    }

    static KeyPair readRsaPrivateKey(char[] input) {
        try {
            if (input.length > 16384) throw new IllegalArgumentException();
            int start = 0;
            while (start < input.length && Character.isWhitespace(input[start])) start++;
            PrivateKey key = start < input.length && input[start] == '{'
                    ? readRsaJwk(input) : readRsaPem(input);
            if (!(key instanceof RSAPrivateCrtKey rsa)
                    || rsa.getModulus().bitLength() < 2048 || rsa.getModulus().bitLength() > 8192) {
                throw new IllegalArgumentException();
            }
            return new KeyPair(KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent())), rsa);
        } catch (IOException | GeneralSecurityException | IllegalArgumentException | IllegalStateException invalid) {
            throw new IllegalArgumentException("Invalid RSA private key");
        }
    }

    private static PrivateKey readRsaPem(char[] pem) throws IOException {
        try (PEMParser parser = new PEMParser(new CharArrayReader(pem))) {
            Object parsed = parser.readObject();
            PrivateKeyInfo info = switch (parsed) {
                case PEMKeyPair pair -> pair.getPrivateKeyInfo();
                case PrivateKeyInfo privateKey -> privateKey;
                case null, default -> throw new IllegalArgumentException("Expected an unencrypted RSA private key");
            };
            if (parser.readObject() != null) throw new IllegalArgumentException("Expected exactly one private key");
            return new JcaPEMKeyConverter().getPrivateKey(info);
        }
    }

    private static PrivateKey readRsaJwk(char[] input) throws IOException, GeneralSecurityException {
        Map<String, BigInteger> values = new HashMap<>();
        HashSet<String> names = new HashSet<>();
        List<String> parameters = List.of("n", "e", "d", "p", "q", "dp", "dq", "qi");
        try (JsonReader reader = new JsonReader(new CharArrayReader(input))) {
            reader.setLenient(false);
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!names.add(name) || name.equals("oth")) throw new IllegalArgumentException();
                if (name.equals("kty")) {
                    if (reader.peek() != JsonToken.STRING || !reader.nextString().equals("RSA")) {
                        throw new IllegalArgumentException();
                    }
                } else if (parameters.contains(name)) {
                    if (reader.peek() != JsonToken.STRING) throw new IllegalArgumentException();
                    String encoded = reader.nextString();
                    if (encoded.length() > 1366 || !encoded.matches("[A-Za-z0-9_-]+")) {
                        throw new IllegalArgumentException();
                    }
                    byte[] bytes = Base64.getUrlDecoder().decode(encoded);
                    try {
                        if (bytes.length == 0 || bytes[0] == 0
                                || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(encoded)) {
                            throw new IllegalArgumentException();
                        }
                        values.put(name, new BigInteger(1, bytes));
                    } finally {
                        Arrays.fill(bytes, (byte) 0);
                    }
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || !names.contains("kty") || values.size() != 8) {
                throw new IllegalArgumentException();
            }
        }
        BigInteger n = values.get("n");
        BigInteger e = values.get("e");
        BigInteger d = values.get("d");
        BigInteger p = values.get("p");
        BigInteger q = values.get("q");
        if (n.bitLength() < 2048 || n.bitLength() > 8192 || e.compareTo(BigInteger.ONE) <= 0
                || e.compareTo(n) >= 0 || d.compareTo(n) >= 0 || p.equals(q)
                || !p.multiply(q).equals(n) || !p.isProbablePrime(80) || !q.isProbablePrime(80)) {
            throw new IllegalArgumentException();
        }
        BigInteger pMinusOne = p.subtract(BigInteger.ONE);
        BigInteger qMinusOne = q.subtract(BigInteger.ONE);
        BigInteger lambda = pMinusOne.divide(pMinusOne.gcd(qMinusOne)).multiply(qMinusOne);
        if (!e.multiply(d).mod(lambda).equals(BigInteger.ONE)
                || !d.mod(pMinusOne).equals(values.get("dp")) || !d.mod(qMinusOne).equals(values.get("dq"))
                || !q.modInverse(p).equals(values.get("qi"))) {
            throw new IllegalArgumentException();
        }
        return KeyFactory.getInstance("RSA").generatePrivate(new RSAPrivateCrtKeySpec(
                n, e, d, p, q, values.get("dp"), values.get("dq"), values.get("qi")));
    }

    public void require(KeyMaterialId id, Set<KeyMaterialPurpose> purposes, KeyMaterialAlgorithm algorithm)
            throws GeneralSecurityException {
        if (owner == null) throw new GeneralSecurityException("Configuration material is unavailable");
        Objects.requireNonNull(id, "id");
        owner.validateExisting(id, purposes, algorithm);
    }

    public List<KeyMaterialId> inventory()
            throws GeneralSecurityException {
        if (owner == null) throw new GeneralSecurityException("Configuration material inventory is unavailable");
        return owner.inventory();
    }

    public KeyMaterialId configurationCipherKey() {
        return new KeyMaterialId("configuration-v1", 1);
    }

    private List<KeyMaterialId> selectSshHostKeys(List<KeyMaterialId> available,
            List<String> references) throws GeneralSecurityException {
        List<KeyMaterialId> selected = new ArrayList<>();
        for (String reference : references) {
            if (reference == null || reference.isBlank()) {
                throw new IllegalArgumentException("SSH host key alias must not be empty");
            }
            KeyMaterialId match = findExactSshHostKey(available, reference);
            if (match == null) {
                throw new GeneralSecurityException("SSH host key reference not found: " + reference);
            }
            if (selected.contains(match)) {
                throw new IllegalArgumentException("Duplicate SSH host key reference: " + reference);
            }
            selected.add(match);
        }
        return List.copyOf(selected);
    }

    private KeyMaterialId findExactSshHostKey(List<KeyMaterialId> available, String alias) {
        for (KeyMaterialId candidate : available) {
            if (alias.equals(candidate.alias())) return candidate;
        }
        return null;
    }

    @Override
    public void close() {
        if (owner != null) owner.close();
    }

    public CertificateIdentity certificateIdentity(TlsMaterialConfiguration configuration)
            throws GeneralSecurityException {
        if (configuration == null) {
            throw new IllegalArgumentException("TLS material configuration must not be null");
        }
        requireAvailable();
        synchronized (owner) {
            KeyMaterialId identity = configuration.identity();
            if (contains(identity.alias(), Set.of())) requirePurpose(identity, KeyMaterialPurpose.TLS_IDENTITY);
            if (configuration.serverIssuerTrustAnchor().isPresent()) {
                KeyMaterialId root = configuration.serverIssuerTrustAnchor().orElseThrow();
                if (contains(root.alias(), Set.of())) requirePurpose(root, KeyMaterialPurpose.TRUST_ANCHOR);
            }
            for (KeyMaterialId root : configuration.clientTrustAnchors()) {
                if (contains(root.alias(), Set.of())) requirePurpose(root, KeyMaterialPurpose.TRUST_ANCHOR);
            }
            return new CertificateIdentity(this, configuration);
        }
    }

    private void requirePurpose(KeyMaterialId id, KeyMaterialPurpose purpose) throws GeneralSecurityException {
        if (!read(id).purposes().contains(purpose)) {
            throw new GeneralSecurityException("Key material purpose does not match: " + id);
        }
    }

    private void requireAvailable() {
        if (owner == null) throw new IllegalStateException("Key material is not available");
    }

    private ConfigurationSecretEnvelope sealConfigurationSecret(
            KeyMaterialId descriptor,
            byte[] plaintext,
            ConfigurationSecretContext context) throws GeneralSecurityException {
        requireBytes(plaintext, "Configuration plaintext");
        requireContext(context);
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(DATA_KEY_BITS, secureRandom);
        SecretKey dataKey = generator.generateKey();
        byte[] nonce = new byte[GCM_NONCE_BYTES];
        secureRandom.nextBytes(nonce);

        Cipher wrappingCipher = Cipher.getInstance(ConfigurationSecretEnvelopeCodec.AES_WRAP);
        wrappingCipher.init(Cipher.WRAP_MODE,
                ((KeyStore.SecretKeyEntry) read(descriptor).content()).getSecretKey());
        byte[] wrappedDataKey = wrappingCipher.wrap(dataKey);

        Cipher encryptionCipher = Cipher.getInstance(ConfigurationSecretEnvelopeCodec.AES_GCM);
        encryptionCipher.init(
                Cipher.ENCRYPT_MODE,
                dataKey,
                new GCMParameterSpec(GCM_TAG_BITS, nonce));
        encryptionCipher.updateAAD(context.authenticatedBytes());
        byte[] ciphertext = encryptionCipher.doFinal(plaintext);
        return new ConfigurationSecretEnvelope(
                ConfigurationSecretEnvelopeCodec.CURRENT_VERSION,
                descriptor.alias(),
                descriptor.version(),
                ConfigurationSecretEnvelopeCodec.AES_WRAP,
                ConfigurationSecretEnvelopeCodec.AES_GCM,
                ConfigurationSecretEnvelopeCodec.BASE64_URL,
                wrappedDataKey,
                nonce,
                ciphertext);
    }

    private byte[] openConfigurationSecret(
            KeyMaterialId descriptor,
            ConfigurationSecretEnvelope envelope,
            ConfigurationSecretContext context) throws GeneralSecurityException {
        requireContext(context);
        requireEnvelopeMatches(descriptor, envelope);
        Cipher wrappingCipher = Cipher.getInstance(ConfigurationSecretEnvelopeCodec.AES_WRAP);
        wrappingCipher.init(Cipher.UNWRAP_MODE,
                ((KeyStore.SecretKeyEntry) read(descriptor).content()).getSecretKey());
        SecretKey dataKey;
        try {
            dataKey = (SecretKey) wrappingCipher.unwrap(
                    envelope.wrappedDataKey(),
                    "AES",
                    Cipher.SECRET_KEY);
        } catch (InvalidKeyException e) {
            throw authenticationFailure(e);
        }

        Cipher decryptionCipher = Cipher.getInstance(ConfigurationSecretEnvelopeCodec.AES_GCM);
        decryptionCipher.init(
                Cipher.DECRYPT_MODE,
                dataKey,
                new GCMParameterSpec(GCM_TAG_BITS, envelope.nonce()));
        decryptionCipher.updateAAD(context.authenticatedBytes());
        try {
            return decryptionCipher.doFinal(envelope.ciphertext());
        } catch (AEADBadTagException e) {
            throw authenticationFailure(e);
        }
    }

    private static void requireEnvelopeMatches(
            KeyMaterialId descriptor,
            ConfigurationSecretEnvelope envelope) throws ConfigurationSecretException {
        if (envelope == null) {
            throw new IllegalArgumentException("Configuration secret envelope must not be null");
        }
        if (!descriptor.alias().equals(envelope.keyAlias())
                || descriptor.version() != envelope.keyVersion()) {
            throw new ConfigurationSecretException(
                    ConfigurationSecretException.Reason.MATERIAL_MISMATCH,
                    "Configuration secret wrapping material does not match the selected material");
        }
        if (envelope.version() != ConfigurationSecretEnvelopeCodec.CURRENT_VERSION
                || !ConfigurationSecretEnvelopeCodec.AES_WRAP.equals(envelope.wrappingAlgorithm())
                || !ConfigurationSecretEnvelopeCodec.AES_GCM.equals(envelope.encryptionAlgorithm())
                || !ConfigurationSecretEnvelopeCodec.BASE64_URL.equals(envelope.encoding())) {
            throw new ConfigurationSecretException(
                    ConfigurationSecretException.Reason.UNSUPPORTED,
                    "Configuration secret envelope metadata is unsupported");
        }
    }

    private static void requireContext(ConfigurationSecretContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Configuration secret context must not be null");
        }
    }

    private static ConfigurationSecretException authenticationFailure(Throwable cause) {
        return new ConfigurationSecretException(
                ConfigurationSecretException.Reason.AUTHENTICATION_FAILED,
                "Configuration secret authentication failed",
                cause);
    }

    private SSLContext createTlsContext(
            KeyMaterialId descriptor,
            List<X509Certificate> identityChain,
            List<KeyMaterialId> clientTrust) throws GeneralSecurityException {
        char[] password = java.util.UUID.randomUUID().toString().toCharArray();
        try {
            KeyStore keyStore = KeyStore.getInstance(KeyMaterialConstants.DEFAULT_KEY_STORE_TYPE);
            keyStore.load(null, password);
            KeyPair keyPair = read(descriptor).keyPair();
            keyStore.setKeyEntry(
                    descriptor.alias(),
                    keyPair.getPrivate(),
                    password,
                    identityChain.toArray(Certificate[]::new));
            KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            factory.init(keyStore, password);
            KeyStore trustStore = KeyStore.getInstance(KeyMaterialConstants.DEFAULT_KEY_STORE_TYPE);
            trustStore.load(null, password);
            for (KeyMaterialId trustedCertificate : clientTrust) {
                trustStore.setCertificateEntry(
                        trustedCertificate.alias(),
                        read(trustedCertificate).trustedCertificate());
            }
            TrustManagerFactory trustFactory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustFactory.init(trustStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(factory.getKeyManagers(), trustFactory.getTrustManagers(), secureRandom);
            return context;
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException("Cannot create TLS key store", e);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static void requireBytes(byte[] value, String label) {
        if (value == null || value.length == 0) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
    }
}
