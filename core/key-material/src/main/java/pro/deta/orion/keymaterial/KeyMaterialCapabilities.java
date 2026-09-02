package pro.deta.orion.keymaterial;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.security.GeneralSecurityException;
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
    private static final String AES_GCM = "AES/GCM/NoPadding";
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
            public EncryptedConfigurationValue encrypt(byte[] plaintext) throws GeneralSecurityException {
                requireBytes(plaintext, "Configuration plaintext");
                byte[] nonce = new byte[GCM_NONCE_BYTES];
                secureRandom.nextBytes(nonce);
                Cipher cipher = initCipher(Cipher.ENCRYPT_MODE, registered, nonce);
                return new EncryptedConfigurationValue(nonce, cipher.doFinal(plaintext));
            }

            @Override
            public byte[] decrypt(EncryptedConfigurationValue encrypted) throws GeneralSecurityException {
                if (encrypted == null) {
                    throw new IllegalArgumentException("Encrypted configuration value must not be null");
                }
                Cipher cipher = initCipher(Cipher.DECRYPT_MODE, registered, encrypted.nonce());
                return cipher.doFinal(encrypted.ciphertext());
            }
        };
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

    private Cipher initCipher(int mode, KeyMaterialDescriptor descriptor, byte[] nonce)
            throws GeneralSecurityException {
        SecretKey secretKey = owner.secretKey(descriptor.alias().value());
        Cipher cipher = Cipher.getInstance(AES_GCM);
        cipher.init(mode, secretKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        return cipher;
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
