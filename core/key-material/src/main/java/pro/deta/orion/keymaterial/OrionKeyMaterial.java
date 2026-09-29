package pro.deta.orion.keymaterial;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import javax.net.ssl.SSLContext;
import javax.security.auth.x500.X500Principal;
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
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Owns runtime key material and its typed capabilities. The configuration cipher is persisted on first seal;
 * a failed material save invalidates this owner so a caller must reopen the durable state before retrying.
 */
public final class OrionKeyMaterial implements AutoCloseable {
    private final KeyMaterialService owner;
    private final KeyMaterialScope.Cluster clusterScope;
    private final ServerIdentityCapability serverIdentity;
    private final AcmeKeyMaterialCapability acme;
    private final TlsCapability tls;
    private boolean closed;

    private OrionKeyMaterial(
            KeyMaterialService owner,
            KeyMaterialScope.Cluster clusterScope,
            ServerIdentityCapability serverIdentity) {
        this.owner = owner;
        this.clusterScope = clusterScope;
        this.serverIdentity = serverIdentity;
        this.acme = acmeCapability();
        this.tls = tlsCapability();
    }

    public static OrionKeyMaterial open(
            KeyMaterialContentStore store,
            KeyMaterialOptions options,
            SigningMaterialSet signingMaterial,
            int activeKeySize) throws IOException, GeneralSecurityException {
        return open(store, options, signingMaterial, activeKeySize, false);
    }

    public static OrionKeyMaterial open(
            KeyMaterialContentStore store,
            KeyMaterialOptions options,
            SigningMaterialSet signingMaterial,
            int activeKeySize,
            boolean createIfMissing) throws IOException, GeneralSecurityException {
        requireRsa(signingMaterial);
        KeyMaterialScope.Cluster clusterScope = requireClusterScope(signingMaterial.active().scope());
        KeyMaterialService service = KeyMaterialService.open(store, options);
        try {
            if (!service.hasDurableSnapshot()) {
                if (!createIfMissing) {
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
                    clusterScope,
                    capabilities.serverIdentity(signingMaterial));
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            service.close();
            throw failure;
        }
    }

    public ServerIdentityCapability serverIdentity() {
        return serverIdentity;
    }

    public AcmeKeyMaterialCapability acme() {
        return acme;
    }

    public TlsCapability tls() {
        return tls;
    }

    public KeyMaterialAdministrationCapability administration() {
        return (alias, purpose, privateKeyPem) -> {
            try {
                if (alias == null || !alias.matches("[a-z0-9][a-z0-9._-]{0,127}")) {
                    throw new IllegalArgumentException("Invalid key material name");
                }
                if (purpose != KeyMaterialPurpose.ACME_ACCOUNT && purpose != KeyMaterialPurpose.TLS_IDENTITY) {
                    throw new IllegalArgumentException("Only ACME account and TLS identity keys can be created");
                }
                KeyPair imported = privateKeyPem == null || privateKeyPem.length == 0
                        ? null : readRsaPrivateKey(privateKeyPem);
                KeyMaterialDescriptor descriptor = new KeyMaterialDescriptor(new KeyMaterialAlias(alias),
                        purpose, KeyMaterialAlgorithm.RSA, new KeyMaterialVersion(1), clusterScope);
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
        };
    }

    private static KeyPair readRsaPrivateKey(char[] input) {
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

    public ConfigurationMaterialCapability configurationMaterial() {
        return new ConfigurationMaterialCapability() {
            @Override
            public void require(KeyMaterialDescriptor descriptor) throws GeneralSecurityException {
                requireOwnerScope(descriptor.scope());
                owner.validateExisting(descriptor);
            }

            @Override
            public void require(TrustedCertificateDescriptor descriptor) throws GeneralSecurityException {
                requireOwnerScope(descriptor.scope());
                owner.validateExisting(descriptor);
            }

            @Override
            public List<KeyMaterialInventoryEntry> inventory() throws GeneralSecurityException {
                return owner.inventory();
            }
        };
    }

    public ConfigurationCipherCapability configurationCipher() {
        KeyMaterialDescriptor descriptor = new KeyMaterialDescriptor(new KeyMaterialAlias("configuration-v1"),
                KeyMaterialPurpose.CONFIGURATION_CIPHER, KeyMaterialAlgorithm.AES,
                new KeyMaterialVersion(1), clusterScope);
        return new ConfigurationCipherCapability() {
            @Override
            public KeyMaterialDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public ConfigurationSecretEnvelope seal(byte[] plaintext, ConfigurationSecretContext context)
                    throws GeneralSecurityException {
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

            @Override
            public byte[] open(ConfigurationSecretEnvelope envelope, ConfigurationSecretContext context)
                    throws GeneralSecurityException {
                synchronized (owner) {
                    return KeyMaterialCapabilities.open(owner, List.of(descriptor))
                            .configurationCipher(descriptor).open(envelope, context);
                }
            }
        };
    }

    public SshHostKeyMaterial sshHostKeyMaterial() {
        return new SshHostKeyMaterial() {
            @Override
            public KeyMaterialScope.Cluster scope() {
                return clusterScope;
            }

            @Override
            public List<KeyMaterialDescriptor> available() throws GeneralSecurityException {
                return owner.privateKeyDescriptors(KeyMaterialPurpose.SSH_HOST, clusterScope);
            }

            @Override
            public void generateIfMissing(Map<KeyMaterialDescriptor, Integer> keySizes)
                    throws IOException, GeneralSecurityException {
                if (keySizes == null) {
                    throw new IllegalArgumentException("SSH host key sizes must not be null");
                }
                synchronized (owner) {
                    for (var entry : keySizes.entrySet()) {
                        KeyMaterialDescriptor descriptor = entry.getKey();
                        requireSshHostKey(descriptor);
                        if (entry.getValue() == null || entry.getValue() < 0) {
                            throw new IllegalArgumentException("SSH host key size must not be negative");
                        }
                        if (owner.containsAlias(descriptor.alias().value())) {
                            owner.validateExisting(descriptor);
                        }
                    }
                    boolean changed = false;
                    for (var entry : keySizes.entrySet()) {
                        KeyMaterialDescriptor descriptor = entry.getKey();
                        if (!owner.containsAlias(descriptor.alias().value())) {
                            owner.generateKeyIfMissing(descriptor, entry.getValue());
                            changed = true;
                        }
                    }
                    if (changed) {
                        owner.save();
                    }
                }
            }

            @Override
            public SshHostKeyCapability load(List<KeyMaterialDescriptor> descriptors)
                    throws GeneralSecurityException {
                if (descriptors == null) {
                    throw new IllegalArgumentException("SSH host key descriptors must not be null");
                }
                for (KeyMaterialDescriptor descriptor : descriptors) {
                    requireSshHostKey(descriptor);
                }
                return KeyMaterialCapabilities.open(owner, descriptors).sshHostKeys(descriptors);
            }

            private void requireSshHostKey(KeyMaterialDescriptor descriptor) {
                if (descriptor == null || descriptor.purpose() != KeyMaterialPurpose.SSH_HOST) {
                    throw new IllegalArgumentException("SSH host key descriptor must have SSH_HOST purpose");
                }
                requireOwnerScope(descriptor.scope());
            }
        };
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        owner.close();
    }

    private AcmeKeyMaterialCapability acmeCapability() {
        return new AcmeKeyMaterialCapability() {
            @Override
            public AcmeKeyMaterial acquire(
                    AcmeMaterialConfiguration configuration,
                    int accountKeySize,
                    int domainKeySize) throws IOException, GeneralSecurityException {
                return select(configuration).acquire(accountKeySize, domainKeySize);
            }

            @Override
            public void installCertificateChain(
                    AcmeMaterialConfiguration configuration,
                    List<? extends Certificate> certificateChain,
                    Optional<X509Certificate> issuerTrustAnchor)
                    throws IOException, GeneralSecurityException {
                select(configuration).installCertificateChain(certificateChain, issuerTrustAnchor);
            }

            @Override
            public Optional<List<X509Certificate>> certificateChain(AcmeMaterialConfiguration configuration)
                    throws GeneralSecurityException {
                if (configuration == null) {
                    throw new IllegalArgumentException("ACME material configuration must not be null");
                }
                requireOwnerScope(configuration.identity().scope());
                if (!owner.containsAlias(configuration.identity().alias().value())) {
                    return Optional.empty();
                }
                List<X509Certificate> chain = select(configuration).certificateChain();
                if (chain.size() == 1 && isStorageCertificate(chain.getFirst())) {
                    return Optional.empty();
                }
                return Optional.of(chain);
            }

            @Override
            public Optional<X509Certificate> issuerTrustAnchor(
                    AcmeMaterialConfiguration configuration) throws GeneralSecurityException {
                return select(configuration).issuerTrustAnchor();
            }
        };
    }

    private TlsCapability tlsCapability() {
        return new TlsCapability() {
            @Override
            public List<X509Certificate> certificateChain(TlsMaterialConfiguration configuration)
                    throws GeneralSecurityException {
                return select(configuration).certificateChain();
            }

            @Override
            public Optional<X509Certificate> serverIssuerTrustAnchor(
                    TlsMaterialConfiguration configuration) throws GeneralSecurityException {
                return select(configuration).serverIssuerTrustAnchor();
            }

            @Override
            public SSLContext createContext(TlsMaterialConfiguration configuration)
                    throws GeneralSecurityException {
                SelectedTlsMaterial selected = select(configuration);
                List<X509Certificate> certificateChain = selected.certificateChain();
                if (certificateChain.size() == 1 && isStorageCertificate(certificateChain.getFirst())) {
                    throw new GeneralSecurityException(
                            "TLS identity must contain an issued certificate chain");
                }
                return selected.createContext();
            }
        };
    }

    private SelectedAcmeKeyMaterial select(AcmeMaterialConfiguration configuration)
            throws GeneralSecurityException {
        if (configuration == null) {
            throw new IllegalArgumentException("ACME material configuration must not be null");
        }
        requireOwnerScope(configuration.account().scope());
        requireOwnerScope(configuration.identity().scope());
        configuration.issuerTrustAnchor().ifPresent(root -> requireOwnerScope(root.scope()));
        List<TrustedCertificateDescriptor> trustedCertificates = configuration
                .issuerTrustAnchor()
                .map(List::of)
                .orElseGet(List::of);
        return KeyMaterialCapabilities.open(
                        owner,
                        List.of(configuration.account(), configuration.identity()),
                        trustedCertificates)
                .acme(
                        configuration.account(),
                        configuration.identity(),
                        configuration.issuerTrustAnchor());
    }

    private SelectedTlsMaterial select(TlsMaterialConfiguration configuration)
            throws GeneralSecurityException {
        if (configuration == null) {
            throw new IllegalArgumentException("TLS material configuration must not be null");
        }
        requireOwnerScope(configuration.identity().scope());
        configuration.serverIssuerTrustAnchor().ifPresent(root -> requireOwnerScope(root.scope()));
        for (TrustedCertificateDescriptor root : configuration.clientTrustAnchors()) {
            requireOwnerScope(root.scope());
        }
        List<TrustedCertificateDescriptor> trustedCertificates = new ArrayList<>();
        configuration.serverIssuerTrustAnchor().ifPresent(trustedCertificates::add);
        for (TrustedCertificateDescriptor root : configuration.clientTrustAnchors()) {
            if (!trustedCertificates.contains(root)) {
                trustedCertificates.add(root);
            }
        }
        return KeyMaterialCapabilities.open(
                        owner,
                        List.of(configuration.identity()),
                        trustedCertificates)
                .tls(
                        configuration.identity(),
                        configuration.serverIssuerTrustAnchor(),
                        configuration.clientTrustAnchors(),
                        configuration.clientAuthentication());
    }

    private void requireOwnerScope(KeyMaterialScope scope) {
        if (!clusterScope.equals(scope)) {
            throw new IllegalArgumentException("Key material reference does not belong to the owner cluster");
        }
    }

    private static boolean isStorageCertificate(X509Certificate certificate) {
        String subject = certificate.getSubjectX500Principal().getName(X500Principal.RFC2253);
        return certificate.getBasicConstraints() < 0
                && certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())
                && subject.contains("O=" + KeyMaterialConstants.STORAGE_CERTIFICATE_ORGANIZATION);
    }

    private static void requireRsa(SigningMaterialSet signingMaterial) {
        if (signingMaterial == null) {
            throw new IllegalArgumentException("Server signing material must not be null");
        }
        if (signingMaterial.active().algorithm() != KeyMaterialAlgorithm.RSA) {
            throw new IllegalArgumentException("JWT server identity material must use RSA");
        }
    }

    private static KeyMaterialScope.Cluster requireClusterScope(KeyMaterialScope scope) {
        if (!(scope instanceof KeyMaterialScope.Cluster cluster)) {
            throw new IllegalArgumentException("Orion key material owner requires cluster-scoped identity");
        }
        return cluster;
    }

}
