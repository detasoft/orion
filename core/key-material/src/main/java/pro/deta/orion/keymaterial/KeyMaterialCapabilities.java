package pro.deta.orion.keymaterial;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class KeyMaterialCapabilities {
    private static final int DATA_KEY_BITS = 256;
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_NONCE_BYTES = 12;

    private final KeyMaterialService owner;
    private final Map<KeyMaterialAlias, KeyMaterialDescriptor> descriptors;
    private final SecureRandom secureRandom;

    private KeyMaterialCapabilities(
            KeyMaterialService owner,
            Map<KeyMaterialAlias, KeyMaterialDescriptor> descriptors) {
        this.owner = owner;
        this.descriptors = descriptors;
        this.secureRandom = new SecureRandom();
    }

    public static KeyMaterialCapabilities open(
            KeyMaterialService owner,
            Collection<KeyMaterialDescriptor> descriptors) throws GeneralSecurityException {
        Map<KeyMaterialAlias, KeyMaterialDescriptor> registered = register(descriptors);
        if (owner == null) {
            throw new IllegalArgumentException("Key material service must not be null");
        }
        for (KeyMaterialDescriptor descriptor : registered.values()) {
            owner.validateExisting(descriptor);
        }
        return new KeyMaterialCapabilities(owner, Map.copyOf(registered));
    }

    public SigningCapability signing(KeyMaterialDescriptor descriptor) {
        KeyMaterialDescriptor registered = requireRegistered(descriptor, KeyMaterialPurpose.SERVER_SIGNING);
        return new SigningCapability() {
            @Override
            public KeyMaterialDescriptor descriptor() {
                return registered;
            }

            @Override
            public byte[] sign(byte[] payload) throws GeneralSecurityException {
                return signWith(registered, payload);
            }
        };
    }

    public ServerIdentityCapability serverIdentity(SigningMaterialSet material) {
        if (material == null) {
            throw new IllegalArgumentException("Server signing material must not be null");
        }
        if (material.active().algorithm() != KeyMaterialAlgorithm.RSA) {
            throw new IllegalArgumentException("JWT server identity material must use RSA");
        }
        KeyMaterialDescriptor active = requireRegistered(
                material.active(), KeyMaterialPurpose.SERVER_SIGNING);
        Map<String, KeyMaterialDescriptor> verification = new LinkedHashMap<>();
        for (KeyMaterialDescriptor descriptor : material.verificationIncludingActive()) {
            KeyMaterialDescriptor registered = requireRegistered(
                    descriptor, KeyMaterialPurpose.SERVER_SIGNING);
            verification.put(registered.alias().value(), registered);
        }
        Map<String, KeyMaterialDescriptor> immutable = Map.copyOf(verification);
        return new ServerIdentityCapability() {
            @Override
            public String activeKeyId() {
                return active.alias().value();
            }

            @Override
            public byte[] sign(byte[] payload) throws GeneralSecurityException {
                return signWith(active, payload);
            }

            @Override
            public boolean hasVerificationKey(String keyId) {
                return keyId != null && immutable.containsKey(keyId);
            }

            @Override
            public boolean verify(
                    String keyId,
                    byte[] payload,
                    byte[] signature) throws GeneralSecurityException {
                if (keyId == null || keyId.isBlank()) {
                    return false;
                }
                KeyMaterialDescriptor descriptor = immutable.get(keyId);
                if (descriptor == null) {
                    return false;
                }
                return KeyMaterialCapabilities.this.verification(List.of(descriptor))
                        .verify(payload, signature);
            }

            @Override
            public List<java.security.PublicKey> publicKeys() throws GeneralSecurityException {
                return List.of(owner.getKeyPair(active.alias().value()).getPublic());
            }

            @Override
            public List<java.security.PublicKey> retainedPublicKeys() throws GeneralSecurityException {
                List<java.security.PublicKey> keys = new ArrayList<>();
                for (KeyMaterialDescriptor descriptor : material.verification()) {
                    keys.add(owner.getKeyPair(descriptor.alias().value()).getPublic());
                }
                return List.copyOf(keys);
            }
        };
    }

    public VerificationCapability verification(List<KeyMaterialDescriptor> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new IllegalArgumentException("Verification material must not be empty");
        }
        List<KeyMaterialDescriptor> registered = new ArrayList<>();
        KeyMaterialPurpose purpose = null;
        for (KeyMaterialDescriptor descriptor : requested) {
            KeyMaterialDescriptor value = requireRegistered(descriptor);
            if (value.purpose() != KeyMaterialPurpose.SERVER_SIGNING
                    && value.purpose() != KeyMaterialPurpose.CERTIFICATE_AUTHORITY) {
                throw new IllegalArgumentException("Verification requires signing-capable material");
            }
            if (purpose == null) {
                purpose = value.purpose();
            } else if (value.purpose() != purpose) {
                throw new IllegalArgumentException("Verification material must have one purpose");
            }
            registered.add(value);
        }
        List<KeyMaterialDescriptor> immutable = List.copyOf(registered);
        return new VerificationCapability() {
            @Override
            public List<KeyMaterialDescriptor> descriptors() {
                return immutable;
            }

            @Override
            public boolean verify(byte[] payload, byte[] signature) throws GeneralSecurityException {
                requireBytes(payload, "Payload");
                requireBytes(signature, "Signature");
                for (KeyMaterialDescriptor descriptor : immutable) {
                    Signature verifier = Signature.getInstance(
                            descriptor.algorithm().requireSignatureAlgorithm());
                    verifier.initVerify(owner.getKeyPair(descriptor.alias().value()).getPublic());
                    verifier.update(payload);
                    if (verifier.verify(signature)) {
                        return true;
                    }
                }
                return false;
            }
        };
    }

    public TlsCapability tls(KeyMaterialDescriptor descriptor) {
        KeyMaterialDescriptor registered = requireRegistered(descriptor, KeyMaterialPurpose.TLS_IDENTITY);
        return new TlsCapability() {
            @Override
            public KeyMaterialDescriptor descriptor() {
                return registered;
            }

            @Override
            public SSLContext createContext() throws GeneralSecurityException {
                return createTlsContext(registered);
            }
        };
    }

    public SshHostKeyCapability sshHostKeys(List<KeyMaterialDescriptor> requested) {
        List<KeyMaterialDescriptor> registered = requireRegistered(requested, KeyMaterialPurpose.SSH_HOST);
        return new SshHostKeyCapability() {
            @Override
            public List<KeyMaterialDescriptor> descriptors() {
                return registered;
            }

            @Override
            public List<KeyPair> keyPairs() throws GeneralSecurityException {
                List<KeyPair> keys = new ArrayList<>();
                for (KeyMaterialDescriptor descriptor : registered) {
                    keys.add(owner.getKeyPair(descriptor.alias().value()));
                }
                return List.copyOf(keys);
            }
        };
    }

    public SshClientKeyCapability sshClientKey(KeyMaterialDescriptor descriptor) {
        KeyMaterialDescriptor registered = requireRegistered(descriptor, KeyMaterialPurpose.SSH_CLIENT);
        return new SshClientKeyCapability() {
            @Override
            public KeyMaterialDescriptor descriptor() {
                return registered;
            }

            @Override
            public KeyPair keyPair() throws GeneralSecurityException {
                KeyPair selected = owner.getKeyPair(registered.alias().value());
                return new KeyPair(selected.getPublic(), selected.getPrivate());
            }
        };
    }

    public CertificateAuthorityCapability certificateAuthority(KeyMaterialDescriptor descriptor) {
        KeyMaterialDescriptor registered = requireRegistered(
                descriptor, KeyMaterialPurpose.CERTIFICATE_AUTHORITY);
        return new CertificateAuthorityCapability() {
            @Override
            public KeyMaterialDescriptor descriptor() {
                return registered;
            }

            @Override
            public byte[] sign(byte[] payload) throws GeneralSecurityException {
                return signWith(registered, payload);
            }

            @Override
            public X509Certificate[] certificateChain() throws GeneralSecurityException {
                Certificate[] certificates = owner.getCertificateChain(registered.alias().value());
                X509Certificate[] chain = new X509Certificate[certificates.length];
                for (int index = 0; index < certificates.length; index++) {
                    if (!(certificates[index] instanceof X509Certificate certificate)) {
                        throw new GeneralSecurityException(
                                "CA certificate chain contains a non-X.509 certificate");
                    }
                    chain[index] = certificate;
                }
                return chain;
            }
        };
    }

    public ConfigurationCipherCapability configurationCipher(KeyMaterialDescriptor descriptor) {
        KeyMaterialDescriptor registered = requireRegistered(
                descriptor, KeyMaterialPurpose.CONFIGURATION_CIPHER);
        return new ConfigurationCipherCapability() {
            @Override
            public KeyMaterialDescriptor descriptor() {
                return registered;
            }

            @Override
            public ConfigurationSecretEnvelope seal(
                    byte[] plaintext,
                    ConfigurationSecretContext context) throws GeneralSecurityException {
                return sealConfigurationSecret(registered, plaintext, context);
            }

            @Override
            public byte[] open(
                    ConfigurationSecretEnvelope envelope,
                    ConfigurationSecretContext context) throws GeneralSecurityException {
                return openConfigurationSecret(registered, envelope, context);
            }
        };
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
        wrappingCipher.init(Cipher.WRAP_MODE, owner.secretKey(descriptor.alias().value()));
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
        wrappingCipher.init(Cipher.UNWRAP_MODE, owner.secretKey(descriptor.alias().value()));
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
                || !descriptor.version().equals(envelope.keyVersion())) {
            throw new ConfigurationSecretException(
                    ConfigurationSecretException.Reason.MATERIAL_MISMATCH,
                    "Configuration secret wrapping material does not match the capability");
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

    private byte[] signWith(KeyMaterialDescriptor descriptor, byte[] payload)
            throws GeneralSecurityException {
        requireBytes(payload, "Payload");
        Signature signer = Signature.getInstance(descriptor.algorithm().requireSignatureAlgorithm());
        signer.initSign(owner.getKeyPair(descriptor.alias().value()).getPrivate());
        signer.update(payload);
        return signer.sign();
    }

    private SSLContext createTlsContext(KeyMaterialDescriptor descriptor) throws GeneralSecurityException {
        char[] password = randomPassword();
        try {
            KeyStore keyStore = KeyStore.getInstance(KeyMaterialConstants.DEFAULT_KEY_STORE_TYPE);
            keyStore.load(null, password);
            KeyPair keyPair = owner.getKeyPair(descriptor.alias().value());
            keyStore.setKeyEntry(
                    descriptor.alias().value(),
                    keyPair.getPrivate(),
                    password,
                    owner.getCertificateChain(descriptor.alias().value()));
            KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            factory.init(keyStore, password);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(factory.getKeyManagers(), null, secureRandom);
            return context;
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException("Cannot create TLS key store", e);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private KeyMaterialDescriptor requireRegistered(
            KeyMaterialDescriptor descriptor,
            KeyMaterialPurpose expectedPurpose) {
        KeyMaterialDescriptor registered = requireRegistered(descriptor);
        if (registered.purpose() != expectedPurpose) {
            throw new IllegalArgumentException("Capability requires " + expectedPurpose + " purpose");
        }
        return registered;
    }

    private KeyMaterialDescriptor requireRegistered(KeyMaterialDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("Key material descriptor must not be null");
        }
        KeyMaterialDescriptor registered = descriptors.get(descriptor.alias());
        if (!descriptor.equals(registered)) {
            throw new IllegalArgumentException(
                    "Key material descriptor is not registered: " + descriptor.alias().value());
        }
        return registered;
    }

    private List<KeyMaterialDescriptor> requireRegistered(
            List<KeyMaterialDescriptor> requested,
            KeyMaterialPurpose purpose) {
        if (requested == null || requested.isEmpty()) {
            throw new IllegalArgumentException(purpose + " material must not be empty");
        }
        List<KeyMaterialDescriptor> registered = new ArrayList<>();
        for (KeyMaterialDescriptor descriptor : requested) {
            registered.add(requireRegistered(descriptor, purpose));
        }
        return List.copyOf(registered);
    }

    private static Map<KeyMaterialAlias, KeyMaterialDescriptor> register(
            Collection<KeyMaterialDescriptor> descriptors) {
        if (descriptors == null || descriptors.isEmpty()) {
            throw new IllegalArgumentException("Key material descriptors must not be empty");
        }
        Map<KeyMaterialAlias, KeyMaterialDescriptor> registered = new LinkedHashMap<>();
        for (KeyMaterialDescriptor descriptor : descriptors) {
            if (descriptor == null) {
                throw new IllegalArgumentException("Key material descriptor must not be null");
            }
            KeyMaterialDescriptor previous = registered.putIfAbsent(descriptor.alias(), descriptor);
            if (previous != null && !previous.equals(descriptor)) {
                if (previous.purpose() != descriptor.purpose()) {
                    throw new IllegalArgumentException(
                            "Key material alias " + descriptor.alias().value()
                                    + " is configured for multiple purposes");
                }
                throw new IllegalArgumentException(
                        "Key material alias has conflicting descriptors: " + descriptor.alias().value());
            }
        }
        return registered;
    }

    private static char[] randomPassword() {
        return java.util.UUID.randomUUID().toString().toCharArray();
    }

    private static void requireBytes(byte[] value, String label) {
        if (value == null || value.length == 0) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
    }
}
