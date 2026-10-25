package pro.deta.orion.keymaterial;

import pro.deta.orion.event.type.OrionEventPublisher;

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
import java.security.PublicKey;
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
import java.util.Optional;

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
    private final SigningIdentity signingIdentity;
    private boolean closed;

    private OrionKeyMaterial(
            KeyMaterialService owner,
            SigningMaterialSet signingMaterial) {
        this.owner = owner;
        this.signingIdentity = signingMaterial == null
                ? SigningIdentity.unavailable() : new SigningIdentity(this, signingMaterial);
    }

    public static OrionKeyMaterial open(KeyMaterialContentStore store, KeyMaterialOptions options)
            throws IOException, GeneralSecurityException {
        return new OrionKeyMaterial(
                KeyMaterialService.open(store, options), null);
    }

    public static OrionKeyMaterial open(
            KeyMaterialContentStore store,
            KeyMaterialOptions options,
            OrionEventPublisher eventPublisher) throws IOException, GeneralSecurityException {
        return new OrionKeyMaterial(
                KeyMaterialService.open(store, options, new KeyMaterialStorageCertificateFactory(), eventPublisher),
                null);
    }

    public static OrionKeyMaterial open(
            KeyMaterialContentStore store,
            KeyMaterialOptions options,
            SigningMaterialSet signingMaterial,
            int activeKeySize) throws IOException, GeneralSecurityException {
        return open(store, options, signingMaterial, activeKeySize, new KeyMaterialCreation(false));
    }

    public static OrionKeyMaterial open(
            KeyMaterialContentStore store,
            KeyMaterialOptions options,
            SigningMaterialSet signingMaterial,
            int activeKeySize,
            KeyMaterialCreation creation) throws IOException, GeneralSecurityException {
        return open(store, options, signingMaterial, activeKeySize, creation, event -> {});
    }

    public static OrionKeyMaterial open(
            KeyMaterialContentStore store,
            KeyMaterialOptions options,
            SigningMaterialSet signingMaterial,
            int activeKeySize,
            KeyMaterialCreation creation,
            OrionEventPublisher eventPublisher) throws IOException, GeneralSecurityException {
        requireRsa(signingMaterial);
        KeyMaterialService service = KeyMaterialService.open(store, options,
                new KeyMaterialStorageCertificateFactory(), eventPublisher);
        try {
            if (!service.hasDurableSnapshot()) {
                if (!creation.allowed()) {
                    throw new GeneralSecurityException(
                            "Key material store is missing and creation was not requested");
                }
                if (!signingMaterial.verification().isEmpty()) {
                    throw new GeneralSecurityException(
                            "A new material store cannot contain retained server identities");
                }
                KeyMaterialModification modification = service.modify();
                try {
                    modification.generateKey(signingMaterial.active(), activeKeySize);
                    modification.apply();
                } finally {
                    modification.discard();
                }
            }
            for (KeyMaterialDescriptor descriptor : signingMaterial.verificationIncludingActive()) {
                service.validateExisting(descriptor);
            }
            return new OrionKeyMaterial(service, signingMaterial);
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            service.close();
            throw failure;
        }
    }

    protected OrionKeyMaterial() {
        owner = null;
        signingIdentity = SigningIdentity.unavailable();
    }

    public static OrionKeyMaterial unavailable() {
        return new OrionKeyMaterial();
    }

    public SigningIdentity signingIdentity() {
        return signingIdentity;
    }

    public boolean hasDurableSnapshot() {
        requireAvailable();
        return owner.hasDurableSnapshot();
    }

    public long version() {
        requireAvailable();
        return owner.revision();
    }

    public KeyMaterialModification modify() {
        requireAvailable();
        return owner.modify();
    }

    public void withModification(KeyMaterialModification.Operation operation)
            throws IOException, GeneralSecurityException {
        Objects.requireNonNull(operation, "operation");
        KeyMaterialModification modification = modify();
        try {
            operation.accept(modification);
        } finally {
            modification.discard();
        }
    }

    public boolean contains(KeyMaterialDescriptor descriptor) throws GeneralSecurityException {
        Objects.requireNonNull(descriptor, "descriptor");
        requireAvailable();
        synchronized (owner) {
            if (!owner.containsAlias(descriptor.alias(), Set.of())) return false;
            owner.validateExisting(descriptor);
            return true;
        }
    }

    public boolean contains(String alias, Set<KeyMaterialPurpose> requiredPurposes) throws GeneralSecurityException {
        requireAvailable();
        Objects.requireNonNull(alias, "alias");
        return owner.containsAlias(alias, requiredPurposes);
    }

    public boolean contains(TrustedCertificateDescriptor descriptor) throws GeneralSecurityException {
        Objects.requireNonNull(descriptor, "descriptor");
        requireAvailable();
        synchronized (owner) {
            if (!owner.containsAlias(descriptor.alias(), Set.of())) return false;
            owner.validateExisting(descriptor);
            return true;
        }
    }

    public List<KeyMaterialDescriptor> privateKeyDescriptors(Set<KeyMaterialPurpose> requiredPurposes)
            throws GeneralSecurityException {
        requireAvailable();
        return owner.privateKeyDescriptors(requiredPurposes);
    }

    public KeyPair keyPair(KeyMaterialDescriptor descriptor) throws GeneralSecurityException {
        requireAvailable();
        synchronized (owner) {
            require(descriptor);
            if (descriptor.algorithm() == KeyMaterialAlgorithm.AES) {
                throw new IllegalArgumentException("Symmetric key material has no key pair");
            }
            return owner.getKeyPair(descriptor.alias());
        }
    }

    public PublicKey publicKey(KeyMaterialDescriptor descriptor) throws GeneralSecurityException {
        return keyPair(descriptor).getPublic();
    }

    public byte[] sign(KeyMaterialDescriptor descriptor, byte[] payload) throws GeneralSecurityException {
        requireAvailable();
        synchronized (owner) {
            requireSigningMaterial(descriptor);
            requireBytes(payload, "Payload");
            Signature signer = Signature.getInstance(descriptor.algorithm().requireSignatureAlgorithm());
            signer.initSign(owner.getKeyPair(descriptor.alias()).getPrivate());
            signer.update(payload);
            return signer.sign();
        }
    }

    public boolean verify(KeyMaterialDescriptor descriptor, byte[] payload, byte[] signature)
            throws GeneralSecurityException {
        requireAvailable();
        synchronized (owner) {
            requireSigningMaterial(descriptor);
            requireBytes(payload, "Payload");
            requireBytes(signature, "Signature");
            Signature verifier = Signature.getInstance(descriptor.algorithm().requireSignatureAlgorithm());
            verifier.initVerify(owner.getKeyPair(descriptor.alias()).getPublic());
            verifier.update(payload);
            return verifier.verify(signature);
        }
    }

    private void requireSigningMaterial(KeyMaterialDescriptor descriptor) throws GeneralSecurityException {
        Objects.requireNonNull(descriptor, "descriptor");
        if (!descriptor.purposes().contains(KeyMaterialPurpose.SERVER_SIGNING)
                && !descriptor.purposes().contains(KeyMaterialPurpose.CERTIFICATE_AUTHORITY)) {
            throw new IllegalArgumentException("Signatures require server signing or CA material");
        }
        require(descriptor);
    }

    public ConfigurationSecretEnvelope seal(KeyMaterialDescriptor descriptor, byte[] plaintext,
            ConfigurationSecretContext context) throws GeneralSecurityException {
        requireAvailable();
        if (plaintext == null || plaintext.length == 0 || context == null) {
            throw new IllegalArgumentException("Configuration secret and context must not be empty");
        }
        synchronized (owner) {
            requireCipher(descriptor);
            prepareConfigurationCipher(descriptor);
            require(descriptor);
            return sealConfigurationSecret(descriptor, plaintext, context);
        }
    }

    public byte[] open(KeyMaterialDescriptor descriptor, ConfigurationSecretEnvelope envelope,
            ConfigurationSecretContext context) throws GeneralSecurityException {
        requireAvailable();
        synchronized (owner) {
            requireCipher(descriptor);
            require(descriptor);
            return openConfigurationSecret(descriptor, envelope, context);
        }
    }

    private void requireCipher(KeyMaterialDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (!descriptor.purposes().contains(KeyMaterialPurpose.CONFIGURATION_CIPHER)) {
            throw new IllegalArgumentException("Encryption requires configuration cipher material");
        }
    }

    public List<X509Certificate> certificateChain(KeyMaterialDescriptor descriptor) throws GeneralSecurityException {
        Objects.requireNonNull(descriptor, "descriptor");
        requireAvailable();
        synchronized (owner) {
            require(descriptor);
            List<X509Certificate> chain = CertificateIdentity.requireX509Chain(
                    Arrays.asList(owner.getCertificateChain(descriptor.alias())));
            if (chain.size() > 1 && CertificateIdentity.isSelfSigned(chain.getLast())) {
                return List.copyOf(chain.subList(0, chain.size() - 1));
            }
            return chain;
        }
    }

    public Optional<List<X509Certificate>> issuedCertificateChain(KeyMaterialDescriptor descriptor)
            throws GeneralSecurityException {
        Objects.requireNonNull(descriptor, "descriptor");
        requireAvailable();
        synchronized (owner) {
            if (!owner.containsAlias(descriptor.alias(), Set.of())) return Optional.empty();
            List<X509Certificate> chain = certificateChain(descriptor);
            if (chain.size() == 1 && CertificateIdentity.isStorageCertificate(chain.getFirst())) {
                return Optional.empty();
            }
            return Optional.of(chain);
        }
    }

    public X509Certificate trustedCertificate(TrustedCertificateDescriptor descriptor)
            throws GeneralSecurityException {
        Objects.requireNonNull(descriptor, "descriptor");
        requireAvailable();
        return owner.getTrustedCertificate(descriptor);
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
                    chain, publicKey(configuration.identity()), identity.issuerTrustAnchor());
            return createTlsContext(configuration.identity(), chain, configuration.clientTrustAnchors());
        }
    }

    public List<KeyPair> sshHostKeyPairs(List<String> references) throws GeneralSecurityException {
        requireAvailable();
        if (references == null) throw new IllegalArgumentException("SSH host key references must not be null");
        synchronized (owner) {
            List<KeyMaterialDescriptor> available = privateKeyDescriptors(Set.of(KeyMaterialPurpose.SSH_HOST));
            List<KeyMaterialDescriptor> selected = references.isEmpty()
                    ? available : selectSshHostKeys(available, references);
            List<KeyPair> keys = new ArrayList<>();
            for (KeyMaterialDescriptor descriptor : selected) keys.add(keyPair(descriptor));
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

    public void require(KeyMaterialDescriptor descriptor) throws GeneralSecurityException {
        if (owner == null) throw new GeneralSecurityException("Configuration material is unavailable");
        Objects.requireNonNull(descriptor, "descriptor");
        owner.validateExisting(descriptor);
    }

    public void require(TrustedCertificateDescriptor descriptor) throws GeneralSecurityException {
        if (owner == null) throw new GeneralSecurityException("Configuration material is unavailable");
        Objects.requireNonNull(descriptor, "descriptor");
        owner.validateExisting(descriptor);
    }

    public List<KeyMaterialInventoryEntry> inventory(Set<KeyMaterialPurpose> requiredPurposes)
            throws GeneralSecurityException {
        if (owner == null) throw new GeneralSecurityException("Configuration material inventory is unavailable");
        return owner.inventory(requiredPurposes);
    }

    public KeyMaterialDescriptor configurationCipherKey() {
        return new KeyMaterialDescriptor("configuration-v1",
                Set.of(KeyMaterialPurpose.CONFIGURATION_CIPHER), KeyMaterialAlgorithm.AES,
                1);
    }

    private void prepareConfigurationCipher(KeyMaterialDescriptor descriptor) throws GeneralSecurityException {
        if (!owner.containsAlias(descriptor.alias(), Set.of())) {
            try {
                withModification(modification -> {
                    modification.generateKey(descriptor, 256);
                    modification.apply();
                });
            } catch (IOException | GeneralSecurityException | RuntimeException failure) {
                owner.close();
                throw new GeneralSecurityException("Cannot persist configuration cipher", failure);
            }
        }
    }

    private List<KeyMaterialDescriptor> selectSshHostKeys(List<KeyMaterialDescriptor> available,
            List<String> references) throws GeneralSecurityException {
        List<KeyMaterialDescriptor> selected = new ArrayList<>();
        for (String reference : references) {
            if (reference == null || reference.isBlank()) {
                throw new IllegalArgumentException("SSH host key alias must not be empty");
            }
            KeyMaterialDescriptor match = findExactSshHostKey(available, reference);
            if (match == null && !isConcreteSshHostKeyAlias(reference)) {
                match = findLatestSshHostKey(available, reference);
            }
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

    private KeyMaterialDescriptor findExactSshHostKey(List<KeyMaterialDescriptor> available, String alias) {
        for (KeyMaterialDescriptor candidate : available) {
            if (alias.equals(candidate.alias())) return candidate;
        }
        return null;
    }

    private KeyMaterialDescriptor findLatestSshHostKey(List<KeyMaterialDescriptor> available, String alias)
            throws GeneralSecurityException {
        KeyMaterialDescriptor match = null;
        for (KeyMaterialDescriptor candidate : available) {
            if (alias.equals(sshHostKeyLogicalAlias(candidate))
                    && (match == null || candidate.version() > match.version())) {
                match = candidate;
            }
        }
        return match;
    }

    private boolean isConcreteSshHostKeyAlias(String alias) {
        int versionMarker = alias.lastIndexOf("-v");
        if (versionMarker <= 0 || versionMarker + 2 == alias.length()) return false;
        for (int index = versionMarker + 2; index < alias.length(); index++) {
            if (!Character.isDigit(alias.charAt(index))) return false;
        }
        return true;
    }

    private String sshHostKeyLogicalAlias(KeyMaterialDescriptor descriptor) throws GeneralSecurityException {
        String suffix = "-v" + descriptor.version();
        String concreteAlias = descriptor.alias();
        if (!concreteAlias.endsWith(suffix) || concreteAlias.length() == suffix.length()) {
            throw new GeneralSecurityException("SSH host key alias does not identify its version: " + concreteAlias);
        }
        return concreteAlias.substring(0, concreteAlias.length() - suffix.length());
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (owner != null) owner.close();
    }

    public CertificateIdentity certificateIdentity(TlsMaterialConfiguration configuration)
            throws GeneralSecurityException {
        if (configuration == null) {
            throw new IllegalArgumentException("TLS material configuration must not be null");
        }
        requireAvailable();
        synchronized (owner) {
            KeyMaterialDescriptor identity = configuration.identity();
            if (contains(identity.alias(), Set.of())
                    || !identity.purposes().equals(Set.of(KeyMaterialPurpose.TLS_IDENTITY))) {
                require(identity);
            }
            if (configuration.serverIssuerTrustAnchor().isPresent()) {
                contains(configuration.serverIssuerTrustAnchor().orElseThrow());
            }
            for (TrustedCertificateDescriptor root : configuration.clientTrustAnchors()) {
                contains(root);
            }
            return new CertificateIdentity(this, configuration);
        }
    }

    private void requireAvailable() {
        if (owner == null) throw new IllegalStateException("Key material is not available");
    }

    private static void requireRsa(SigningMaterialSet signingMaterial) {
        if (signingMaterial == null) {
            throw new IllegalArgumentException("Server signing material must not be null");
        }
        if (signingMaterial.active().algorithm() != KeyMaterialAlgorithm.RSA) {
            throw new IllegalArgumentException("JWT server identity material must use RSA");
        }
    }

    private ConfigurationSecretEnvelope sealConfigurationSecret(
            KeyMaterialDescriptor descriptor,
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
        wrappingCipher.init(Cipher.WRAP_MODE, owner.secretKey(descriptor.alias()));
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
            KeyMaterialDescriptor descriptor,
            ConfigurationSecretEnvelope envelope,
            ConfigurationSecretContext context) throws GeneralSecurityException {
        requireContext(context);
        requireEnvelopeMatches(descriptor, envelope);
        Cipher wrappingCipher = Cipher.getInstance(ConfigurationSecretEnvelopeCodec.AES_WRAP);
        wrappingCipher.init(Cipher.UNWRAP_MODE, owner.secretKey(descriptor.alias()));
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
            KeyMaterialDescriptor descriptor,
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
            KeyMaterialDescriptor descriptor,
            List<X509Certificate> identityChain,
            List<TrustedCertificateDescriptor> clientTrust) throws GeneralSecurityException {
        char[] password = java.util.UUID.randomUUID().toString().toCharArray();
        try {
            KeyStore keyStore = KeyStore.getInstance(KeyMaterialConstants.DEFAULT_KEY_STORE_TYPE);
            keyStore.load(null, password);
            KeyPair keyPair = owner.getKeyPair(descriptor.alias());
            keyStore.setKeyEntry(
                    descriptor.alias(),
                    keyPair.getPrivate(),
                    password,
                    identityChain.toArray(Certificate[]::new));
            KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            factory.init(keyStore, password);
            KeyStore trustStore = KeyStore.getInstance(KeyMaterialConstants.DEFAULT_KEY_STORE_TYPE);
            trustStore.load(null, password);
            for (TrustedCertificateDescriptor trustedCertificate : clientTrust) {
                trustStore.setCertificateEntry(
                        trustedCertificate.alias(),
                        owner.getTrustedCertificate(trustedCertificate));
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
