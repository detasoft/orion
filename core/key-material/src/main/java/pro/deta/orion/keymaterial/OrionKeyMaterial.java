package pro.deta.orion.keymaterial;

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
import javax.net.ssl.SSLContext;
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
 * The legacy configuration cipher is persisted on first seal;
 * a failed material save invalidates this owner so a caller must reopen the durable state before retrying.
 */
public class OrionKeyMaterial implements AutoCloseable {
    private final KeyMaterialService owner;
    private final SigningIdentity signingIdentity;
    private boolean closed;

    private OrionKeyMaterial(
            KeyMaterialService owner,
            SigningIdentity signingIdentity) {
        this.owner = owner;
        this.signingIdentity = signingIdentity;
    }

    public static OrionKeyMaterial open(KeyMaterialContentStore store, KeyMaterialOptions options)
            throws IOException, GeneralSecurityException {
        return new OrionKeyMaterial(
                KeyMaterialService.open(store, options), SigningIdentity.unavailable());
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
        requireRsa(signingMaterial);
        KeyMaterialService service = KeyMaterialService.open(store, options);
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
                service.generateKeyIfMissing(signingMaterial.active(), activeKeySize);
                service.save();
            }
            List<KeyMaterialDescriptor> descriptors = signingMaterial.verificationIncludingActive();
            KeyMaterialCapabilities capabilities = KeyMaterialCapabilities.open(service, descriptors);
            return new OrionKeyMaterial(
                    service,
                    capabilities.signingIdentity(signingMaterial));
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
            if (!owner.containsAlias(descriptor.alias().value())) return false;
            owner.validateExisting(descriptor);
            return true;
        }
    }

    public boolean contains(TrustedCertificateDescriptor descriptor) throws GeneralSecurityException {
        Objects.requireNonNull(descriptor, "descriptor");
        requireAvailable();
        synchronized (owner) {
            if (!owner.containsAlias(descriptor.alias().value())) return false;
            owner.validateExisting(descriptor);
            return true;
        }
    }

    public List<KeyMaterialDescriptor> privateKeyDescriptors(KeyMaterialPurpose purpose)
            throws GeneralSecurityException {
        requireAvailable();
        Objects.requireNonNull(purpose, "purpose");
        return owner.privateKeyDescriptors(purpose);
    }

    public KeyPair keyPair(KeyMaterialDescriptor descriptor) throws GeneralSecurityException {
        requireAvailable();
        synchronized (owner) {
            require(descriptor);
            if (descriptor.algorithm() == KeyMaterialAlgorithm.AES) {
                throw new IllegalArgumentException("Symmetric key material has no key pair");
            }
            return owner.getKeyPair(descriptor.alias().value());
        }
    }

    public PublicKey publicKey(KeyMaterialDescriptor descriptor) throws GeneralSecurityException {
        return keyPair(descriptor).getPublic();
    }

    public byte[] sign(KeyMaterialDescriptor descriptor, byte[] payload) throws GeneralSecurityException {
        requireAvailable();
        synchronized (owner) {
            KeyMaterialCapabilities selected = signingOperations(descriptor);
            return selected.signWith(descriptor, payload);
        }
    }

    public boolean verify(KeyMaterialDescriptor descriptor, byte[] payload, byte[] signature)
            throws GeneralSecurityException {
        requireAvailable();
        synchronized (owner) {
            KeyMaterialCapabilities selected = signingOperations(descriptor);
            return selected.verifyWith(descriptor, payload, signature);
        }
    }

    private KeyMaterialCapabilities signingOperations(KeyMaterialDescriptor descriptor)
            throws GeneralSecurityException {
        Objects.requireNonNull(descriptor, "descriptor");
        if (descriptor.purpose() != KeyMaterialPurpose.SERVER_SIGNING
                && descriptor.purpose() != KeyMaterialPurpose.CERTIFICATE_AUTHORITY) {
            throw new IllegalArgumentException("Signatures require server signing or CA material");
        }
        return KeyMaterialCapabilities.open(owner, List.of(descriptor));
    }

    public ConfigurationSecretEnvelope seal(KeyMaterialDescriptor descriptor, byte[] plaintext,
            ConfigurationSecretContext context) throws GeneralSecurityException {
        requireAvailable();
        synchronized (owner) {
            requireCipher(descriptor);
            return KeyMaterialCapabilities.open(owner, List.of(descriptor))
                    .sealConfigurationSecret(descriptor, plaintext, context);
        }
    }

    public byte[] open(KeyMaterialDescriptor descriptor, ConfigurationSecretEnvelope envelope,
            ConfigurationSecretContext context) throws GeneralSecurityException {
        requireAvailable();
        synchronized (owner) {
            requireCipher(descriptor);
            return KeyMaterialCapabilities.open(owner, List.of(descriptor))
                    .openConfigurationSecret(descriptor, envelope, context);
        }
    }

    private void requireCipher(KeyMaterialDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (descriptor.purpose() != KeyMaterialPurpose.CONFIGURATION_CIPHER) {
            throw new IllegalArgumentException("Encryption requires configuration cipher material");
        }
    }

    public List<X509Certificate> certificateChain(KeyMaterialDescriptor descriptor) throws GeneralSecurityException {
        Objects.requireNonNull(descriptor, "descriptor");
        requireAvailable();
        synchronized (owner) {
            return KeyMaterialCapabilities.open(owner, List.of(descriptor)).x509CertificateChain(descriptor);
        }
    }

    public Optional<List<X509Certificate>> issuedCertificateChain(KeyMaterialDescriptor descriptor)
            throws GeneralSecurityException {
        Objects.requireNonNull(descriptor, "descriptor");
        requireAvailable();
        synchronized (owner) {
            if (!owner.containsAlias(descriptor.alias().value())) return Optional.empty();
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
            return certificateIdentity(configuration).createContext();
        }
    }

    public List<KeyPair> sshHostKeyPairs(List<SshHostKeyReference> references) throws GeneralSecurityException {
        requireAvailable();
        if (references == null) throw new IllegalArgumentException("SSH host key references must not be null");
        synchronized (owner) {
            List<KeyMaterialDescriptor> available = privateKeyDescriptors(KeyMaterialPurpose.SSH_HOST);
            List<KeyMaterialDescriptor> selected = references.isEmpty()
                    ? available : selectSshHostKeys(available, references);
            List<KeyPair> keys = new ArrayList<>();
            for (KeyMaterialDescriptor descriptor : selected) keys.add(keyPair(descriptor));
            return List.copyOf(keys);
        }
    }

    public void createKey(String alias, KeyMaterialPurpose purpose, char[] privateKeyPem)
            throws IOException, GeneralSecurityException {
        try {
            if (owner == null) throw new GeneralSecurityException("Key material administration is unavailable");
            if (alias == null || !alias.matches("[a-z0-9][a-z0-9._-]{0,127}")) {
                throw new IllegalArgumentException("Invalid key material name");
            }
            if (purpose != KeyMaterialPurpose.ACME_ACCOUNT && purpose != KeyMaterialPurpose.TLS_IDENTITY) {
                throw new IllegalArgumentException("Only ACME account and TLS identity keys can be created");
            }
            KeyPair imported = privateKeyPem == null || privateKeyPem.length == 0
                    ? null : readRsaPrivateKey(privateKeyPem);
            KeyMaterialDescriptor descriptor = new KeyMaterialDescriptor(new KeyMaterialAlias(alias),
                    purpose, KeyMaterialAlgorithm.RSA, new KeyMaterialVersion(1));
            synchronized (owner) {
                if (owner.containsAlias(alias)) {
                    throw new IllegalArgumentException("Key material name already exists");
                }
                if (imported == null) owner.generateKeyIfMissing(descriptor, 3072);
                else owner.importKey(descriptor, imported);
                try {
                    owner.save();
                } catch (IOException | GeneralSecurityException | RuntimeException failure) {
                    owner.close();
                    throw failure;
                }
            }
        } finally {
            if (privateKeyPem != null) Arrays.fill(privateKeyPem, '\0');
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

    public List<KeyMaterialInventoryEntry> inventory() throws GeneralSecurityException {
        if (owner == null) throw new GeneralSecurityException("Configuration material inventory is unavailable");
        return owner.inventory();
    }

    public ConfigurationSecretCipher configurationCipher() {
        if (owner == null) return ConfigurationSecretCipher.unavailable();
        return new ConfigurationSecretCipher(configurationCipherKey(), this);
    }

    public KeyMaterialDescriptor configurationCipherKey() {
        requireAvailable();
        return new KeyMaterialDescriptor(new KeyMaterialAlias("configuration-v1"),
                KeyMaterialPurpose.CONFIGURATION_CIPHER, KeyMaterialAlgorithm.AES,
                new KeyMaterialVersion(1));
    }

    ConfigurationSecretEnvelope sealConfigurationSecret(KeyMaterialDescriptor descriptor,
            byte[] plaintext, ConfigurationSecretContext context) throws GeneralSecurityException {
        if (plaintext == null || plaintext.length == 0 || context == null) {
            throw new IllegalArgumentException("Configuration secret and context must not be empty");
        }
        synchronized (owner) {
            if (!owner.containsAlias(descriptor.alias().value())) {
                try {
                    owner.generateSecretKeyIfMissing(descriptor, 256);
                    owner.save();
                } catch (IOException | GeneralSecurityException | RuntimeException failure) {
                    owner.close();
                    throw new GeneralSecurityException("Cannot persist configuration cipher", failure);
                }
            }
            return KeyMaterialCapabilities.open(owner, List.of(descriptor))
                    .configurationCipher(descriptor).seal(plaintext, context);
        }
    }

    byte[] openConfigurationSecret(KeyMaterialDescriptor descriptor, ConfigurationSecretEnvelope envelope,
            ConfigurationSecretContext context) throws GeneralSecurityException {
        synchronized (owner) {
            return KeyMaterialCapabilities.open(owner, List.of(descriptor))
                    .configurationCipher(descriptor).open(envelope, context);
        }
    }

    public SshHostKeyCapability sshHostKeys(List<SshHostKeyReference> references)
            throws IOException, GeneralSecurityException {
        requireAvailable();
        if (references == null) {
            throw new IllegalArgumentException("SSH host key references must not be null");
        }
        synchronized (owner) {
            List<KeyMaterialDescriptor> available = owner.privateKeyDescriptors(
                    KeyMaterialPurpose.SSH_HOST);
            if (references.isEmpty() && available.isEmpty()) {
                KeyMaterialDescriptor rsa = defaultSshHostKey("ssh-host-rsa", KeyMaterialAlgorithm.RSA);
                KeyMaterialDescriptor ec = defaultSshHostKey("ssh-host-ec", KeyMaterialAlgorithm.EC);
                for (KeyMaterialDescriptor descriptor : List.of(rsa, ec)) {
                    if (owner.containsAlias(descriptor.alias().value())) {
                        owner.validateExisting(descriptor);
                    }
                }
                owner.generateKeyIfMissing(rsa, KeyMaterialConstants.RSA_KEY_SIZE_BITS);
                owner.generateKeyIfMissing(ec, KeyMaterialConstants.EC_KEY_SIZE_BITS);
                owner.save();
                available = owner.privateKeyDescriptors(KeyMaterialPurpose.SSH_HOST);
            }
            List<KeyMaterialDescriptor> selected = references.isEmpty()
                    ? available : selectSshHostKeys(available, references);
            return KeyMaterialCapabilities.open(owner, selected).sshHostKeys(selected);
        }
    }

    private KeyMaterialDescriptor defaultSshHostKey(String logicalAlias, KeyMaterialAlgorithm algorithm) {
        KeyMaterialVersion version = new KeyMaterialVersion(1);
        return new KeyMaterialDescriptor(new KeyMaterialAlias(logicalAlias + "-v" + version.value()),
                KeyMaterialPurpose.SSH_HOST, algorithm, version);
    }

    private List<KeyMaterialDescriptor> selectSshHostKeys(List<KeyMaterialDescriptor> available,
            List<SshHostKeyReference> references) throws GeneralSecurityException {
        List<KeyMaterialDescriptor> selected = new ArrayList<>();
        for (SshHostKeyReference reference : references) {
            if (reference == null) {
                throw new IllegalArgumentException("SSH host key reference must not be null");
            }
            KeyMaterialDescriptor match = findExactSshHostKey(available, reference.alias());
            if (match == null && !isConcreteSshHostKeyAlias(reference.alias())) {
                match = findLatestSshHostKey(available, reference.alias());
            }
            if (match == null) {
                throw new GeneralSecurityException("SSH host key reference not found: " + reference.alias());
            }
            if (selected.contains(match)) {
                throw new IllegalArgumentException("Duplicate SSH host key reference: " + reference.alias());
            }
            selected.add(match);
        }
        return List.copyOf(selected);
    }

    private KeyMaterialDescriptor findExactSshHostKey(List<KeyMaterialDescriptor> available, String alias) {
        for (KeyMaterialDescriptor candidate : available) {
            if (alias.equals(candidate.alias().value())) return candidate;
        }
        return null;
    }

    private KeyMaterialDescriptor findLatestSshHostKey(List<KeyMaterialDescriptor> available, String alias)
            throws GeneralSecurityException {
        KeyMaterialDescriptor match = null;
        for (KeyMaterialDescriptor candidate : available) {
            if (alias.equals(sshHostKeyLogicalAlias(candidate))
                    && (match == null || candidate.version().compareTo(match.version()) > 0)) {
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
        String suffix = "-v" + descriptor.version().value();
        String concreteAlias = descriptor.alias().value();
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

    public AcmeKeyMaterial acquireAcmeKeys(AcmeMaterialConfiguration configuration,
            int accountKeySize, int domainKeySize) throws IOException, GeneralSecurityException {
        requireAcmeConfiguration(configuration);
        return KeyMaterialCapabilities.open(owner, List.of(configuration.account(), configuration.identity()),
                configuration.issuerTrustAnchor().map(List::of).orElseGet(List::of))
                .acquireAcmeKeys(configuration.account(), configuration.identity(),
                        configuration.issuerTrustAnchor(), accountKeySize, domainKeySize);
    }

    public CertificateIdentity certificateIdentity(AcmeMaterialConfiguration configuration)
            throws GeneralSecurityException {
        requireAcmeConfiguration(configuration);
        return KeyMaterialCapabilities.open(owner, List.of(configuration.account(), configuration.identity()),
                configuration.issuerTrustAnchor().map(List::of).orElseGet(List::of))
                .certificateIdentity(configuration.identity(), configuration.issuerTrustAnchor(),
                        List.of(), TlsClientAuthentication.DISABLED);
    }

    public CertificateIdentity certificateIdentity(TlsMaterialConfiguration configuration)
            throws GeneralSecurityException {
        if (configuration == null) {
            throw new IllegalArgumentException("TLS material configuration must not be null");
        }
        requireAvailable();
        List<TrustedCertificateDescriptor> trusted = new ArrayList<>();
        configuration.serverIssuerTrustAnchor().ifPresent(trusted::add);
        for (TrustedCertificateDescriptor root : configuration.clientTrustAnchors()) {
            if (!trusted.contains(root)) trusted.add(root);
        }
        return KeyMaterialCapabilities.open(owner, List.of(configuration.identity()), trusted)
                .certificateIdentity(configuration.identity(), configuration.serverIssuerTrustAnchor(),
                        configuration.clientTrustAnchors(), configuration.clientAuthentication());
    }

    private void requireAcmeConfiguration(AcmeMaterialConfiguration configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("ACME material configuration must not be null");
        }
        requireAvailable();
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

}
