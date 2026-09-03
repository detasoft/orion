package pro.deta.orion.keymaterial;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyMaterialCapabilitiesTest {
    private static final KeyMaterialScope CLUSTER = KeyMaterialScope.cluster("orion-prod");
    private static final KeyMaterialScope NODE = KeyMaterialScope.node("orion-prod", "node-7");

    @Test
    void signsAndVerifiesWithoutExposingPrivateKey() throws Exception {
        try (KeyMaterialService service = service()) {
            KeyMaterialDescriptor signing = descriptor(
                    "server-signing-v1",
                    KeyMaterialPurpose.SERVER_SIGNING,
                    KeyMaterialAlgorithm.RSA,
                    1,
                    CLUSTER);
            service.generateKeyIfMissing(signing, 2048);
            KeyMaterialCapabilities capabilities = KeyMaterialCapabilities.open(service, List.of(signing));

            byte[] payload = "short-lived-token".getBytes(StandardCharsets.UTF_8);
            byte[] signature = capabilities.signing(signing).sign(payload);

            assertThat(capabilities.verification(List.of(signing)).verify(payload, signature)).isTrue();
            assertThat(capabilities.verification(List.of(signing)).verify(
                    "modified".getBytes(StandardCharsets.UTF_8), signature)).isFalse();
            assertThat(SigningCapability.class.getMethods())
                    .noneMatch(method -> method.getReturnType().getSimpleName().contains("PrivateKey"));
        }
    }

    @Test
    void reloadsAndUsesGeneratedEd25519SigningMaterial() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialDescriptor signing = descriptor(
                "server-signing-ed25519-v1",
                KeyMaterialPurpose.SERVER_SIGNING,
                KeyMaterialAlgorithm.ED25519,
                1,
                CLUSTER);
        try (KeyMaterialService initial = KeyMaterialService.open(store, options(true))) {
            initial.generateKeyIfMissing(signing, 0);
            initial.save();
        }

        try (KeyMaterialService reloaded = KeyMaterialService.open(store, options(false))) {
            KeyMaterialCapabilities capabilities = KeyMaterialCapabilities.open(reloaded, List.of(signing));
            byte[] payload = "ed25519-payload".getBytes(StandardCharsets.UTF_8);
            byte[] signature = capabilities.signing(signing).sign(payload);

            assertThat(capabilities.verification(List.of(signing)).verify(payload, signature)).isTrue();
        }
    }

    @Test
    void reloadsSshClientKeyMaterialAndRejectsWrongDescriptorMetadata() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialDescriptor rsaClient = descriptor(
                "provisioning-client-v1",
                KeyMaterialPurpose.SSH_CLIENT,
                KeyMaterialAlgorithm.RSA,
                1,
                NODE);
        KeyMaterialDescriptor ed25519Client = descriptor(
                "provisioning-client-ed25519-v1",
                KeyMaterialPurpose.SSH_CLIENT,
                KeyMaterialAlgorithm.ED25519,
                1,
                NODE);
        KeyMaterialDescriptor host = descriptor(
                "provisioning-host-v1",
                KeyMaterialPurpose.SSH_HOST,
                KeyMaterialAlgorithm.RSA,
                1,
                NODE);
        KeyPair generatedRsa;
        KeyPair generatedEd25519;
        try (KeyMaterialService initial = KeyMaterialService.open(store, options(true))) {
            generatedRsa = initial.generateKeyIfMissing(rsaClient, 2048);
            generatedEd25519 = initial.generateKeyIfMissing(ed25519Client, 0);
            initial.generateKeyIfMissing(host, 2048);
            initial.save();
        }

        try (KeyMaterialService reloaded = KeyMaterialService.open(store, options(false))) {
            KeyMaterialCapabilities capabilities = KeyMaterialCapabilities.open(
                    reloaded, List.of(rsaClient, ed25519Client, host));
            KeyPair selectedRsa = capabilities.sshClientKey(rsaClient).keyPair();
            KeyPair selectedEd25519 = capabilities.sshClientKey(ed25519Client).keyPair();

            assertThat(selectedRsa.getPublic().getEncoded())
                    .isEqualTo(generatedRsa.getPublic().getEncoded());
            assertThat(selectedRsa.getPrivate().getEncoded())
                    .isEqualTo(generatedRsa.getPrivate().getEncoded());
            assertThat(selectedEd25519.getPublic().getEncoded())
                    .isEqualTo(generatedEd25519.getPublic().getEncoded());
            assertThat(selectedEd25519.getPrivate().getEncoded())
                    .isEqualTo(generatedEd25519.getPrivate().getEncoded());

            KeyMaterialDescriptor wrongPurpose = descriptor(
                    rsaClient.alias().value(),
                    KeyMaterialPurpose.SSH_HOST,
                    rsaClient.algorithm(),
                    rsaClient.version().value(),
                    rsaClient.scope());
            KeyMaterialDescriptor wrongScope = descriptor(
                    rsaClient.alias().value(),
                    rsaClient.purpose(),
                    rsaClient.algorithm(),
                    rsaClient.version().value(),
                    CLUSTER);
            KeyMaterialDescriptor wrongAlgorithm = descriptor(
                    rsaClient.alias().value(),
                    rsaClient.purpose(),
                    KeyMaterialAlgorithm.EC,
                    rsaClient.version().value(),
                    rsaClient.scope());

            assertThatThrownBy(() -> capabilities.sshClientKey(wrongPurpose))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("registered");
            assertThatThrownBy(() -> capabilities.sshClientKey(wrongScope))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("registered");
            assertThatThrownBy(() -> capabilities.sshClientKey(wrongAlgorithm))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("registered");
            assertThatThrownBy(() -> capabilities.sshClientKey(host))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SSH_CLIENT");
            assertThatThrownBy(() -> descriptor(
                    "invalid-client-v1",
                    KeyMaterialPurpose.SSH_CLIENT,
                    KeyMaterialAlgorithm.AES,
                    1,
                    NODE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("AES")
                    .hasMessageContaining("configuration ciphers");
        }
    }

    @Test
    void validatesExistingEntriesBeforeReuseOrActivation() throws Exception {
        try (KeyMaterialService service = service()) {
            KeyMaterialDescriptor rsa = descriptor(
                    "host-key-v1", KeyMaterialPurpose.SSH_HOST, KeyMaterialAlgorithm.RSA, 1, NODE);
            service.generateKeyIfMissing(rsa, 2048);
            KeyMaterialDescriptor incompatible = descriptor(
                    "host-key-v1", KeyMaterialPurpose.SSH_HOST, KeyMaterialAlgorithm.EC, 1, NODE);

            assertThatThrownBy(() -> service.generateKeyIfMissing(incompatible, 256))
                    .isInstanceOf(Exception.class)
                    .hasMessageContaining("algorithm")
                    .hasMessageContaining("host-key-v1");
            assertThatThrownBy(() -> KeyMaterialCapabilities.open(service, List.of(incompatible)))
                    .isInstanceOf(Exception.class)
                    .hasMessageContaining("algorithm")
                    .hasMessageContaining("host-key-v1");
        }
    }

    @Test
    void rejectsReusingExistingAliasWithDifferentVersionOrScopeAfterReload() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialDescriptor original = descriptor(
                "server-signing-v1",
                KeyMaterialPurpose.SERVER_SIGNING,
                KeyMaterialAlgorithm.RSA,
                1,
                CLUSTER);
        try (KeyMaterialService initial = KeyMaterialService.open(store, options(true))) {
            initial.generateKeyIfMissing(original, 2048);
            initial.save();
        }

        try (KeyMaterialService reloaded = KeyMaterialService.open(store, options(false))) {
            KeyMaterialDescriptor changedVersion = descriptor(
                    "server-signing-v1",
                    KeyMaterialPurpose.SERVER_SIGNING,
                    KeyMaterialAlgorithm.RSA,
                    2,
                    CLUSTER);
            KeyMaterialDescriptor changedScope = descriptor(
                    "server-signing-v1",
                    KeyMaterialPurpose.SERVER_SIGNING,
                    KeyMaterialAlgorithm.RSA,
                    1,
                    NODE);

            assertThatThrownBy(() -> reloaded.generateKeyIfMissing(changedVersion, 2048))
                    .isInstanceOf(Exception.class)
                    .hasMessageContaining("version");
            assertThatThrownBy(() -> KeyMaterialCapabilities.open(reloaded, List.of(changedScope)))
                    .isInstanceOf(Exception.class)
                    .hasMessageContaining("scope");
        }
    }

    @Test
    void distinguishesClusterAndNodeScopesWhoseDisplayNamesCollideAfterReload() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialDescriptor clusterMaterial = descriptor(
                "ambiguous-scope-v1",
                KeyMaterialPurpose.SERVER_SIGNING,
                KeyMaterialAlgorithm.RSA,
                1,
                KeyMaterialScope.cluster("a/node:b"));
        try (KeyMaterialService initial = KeyMaterialService.open(store, options(true))) {
            initial.generateKeyIfMissing(clusterMaterial, 2048);
            initial.save();
        }

        KeyMaterialDescriptor nodeMaterial = descriptor(
                "ambiguous-scope-v1",
                KeyMaterialPurpose.SERVER_SIGNING,
                KeyMaterialAlgorithm.RSA,
                1,
                KeyMaterialScope.node("a", "b"));
        assertThat(clusterMaterial.scope().canonicalName())
                .isNotEqualTo(nodeMaterial.scope().canonicalName());
        try (KeyMaterialService reloaded = KeyMaterialService.open(store, options(false))) {
            assertThatThrownBy(() -> KeyMaterialCapabilities.open(reloaded, List.of(nodeMaterial)))
                    .isInstanceOf(Exception.class)
                    .hasMessageContaining("scope");
        }
    }

    @Test
    void exposesTlsSshAndCaThroughPurposeSpecificCapabilities() throws Exception {
        try (KeyMaterialService service = service()) {
            KeyMaterialDescriptor tls = descriptor(
                    "https-v1", KeyMaterialPurpose.TLS_IDENTITY, KeyMaterialAlgorithm.RSA, 1, NODE);
            KeyMaterialDescriptor ssh = descriptor(
                    "ssh-host-v1", KeyMaterialPurpose.SSH_HOST, KeyMaterialAlgorithm.RSA, 1, NODE);
            KeyMaterialDescriptor ca = descriptor(
                    "orion-ca-v1",
                    KeyMaterialPurpose.CERTIFICATE_AUTHORITY,
                    KeyMaterialAlgorithm.RSA,
                    1,
                    CLUSTER);
            service.generateKeyIfMissing(tls, 2048);
            service.generateKeyIfMissing(ssh, 2048);
            service.generateKeyIfMissing(ca, 2048);
            KeyMaterialCapabilities capabilities = KeyMaterialCapabilities.open(service, List.of(tls, ssh, ca));

            SSLContext context = capabilities.tls(tls).createContext();
            KeyPair sshHostKey = capabilities.sshHostKeys(List.of(ssh)).keyPairs().getFirst();
            byte[] payload = "certificate-body".getBytes(StandardCharsets.UTF_8);
            byte[] signature = capabilities.certificateAuthority(ca).sign(payload);

            assertThat(context.getProtocol()).isEqualTo("TLS");
            assertThat(sshHostKey.getPrivate().getAlgorithm()).isEqualTo("RSA");
            assertThat(capabilities.verification(List.of(ca)).verify(payload, signature)).isTrue();
            assertThat(capabilities.certificateAuthority(ca).certificateChain()).hasSize(1);
        }
    }

    @Test
    void sealsConfigurationEnvelopeAcrossMaterialReload() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialDescriptor cipher = descriptor(
                "configuration-v1",
                KeyMaterialPurpose.CONFIGURATION_CIPHER,
                KeyMaterialAlgorithm.AES,
                1,
                CLUSTER);
        ConfigurationSecretContext context = new ConfigurationSecretContext("github-token", "access-token");
        byte[] plaintext = "database-password".getBytes(StandardCharsets.UTF_8);
        ConfigurationSecretEnvelopeCodec codec = new ConfigurationSecretEnvelopeCodec();
        String serializedEnvelope;
        try (KeyMaterialService service = KeyMaterialService.open(store, options(true))) {
            service.generateSecretKeyIfMissing(cipher, 256);
            service.save();
            KeyMaterialCapabilities capabilities = KeyMaterialCapabilities.open(service, List.of(cipher));

            ConfigurationSecretEnvelope envelope = capabilities
                    .configurationCipher(cipher)
                    .seal(plaintext, context);
            serializedEnvelope = codec.serialize(envelope);
        }

        try (KeyMaterialService reloaded = KeyMaterialService.open(store, options(false))) {
            KeyMaterialCapabilities capabilities = KeyMaterialCapabilities.open(reloaded, List.of(cipher));
            ConfigurationSecretEnvelope envelope = codec.parse(serializedEnvelope);

            assertThat(envelope.keyAlias()).isEqualTo(cipher.alias());
            assertThat(envelope.keyVersion()).isEqualTo(cipher.version());
            assertThat(envelope.wrappingAlgorithm()).isEqualTo("AESWrap");
            assertThat(envelope.encryptionAlgorithm()).isEqualTo("AES/GCM/NoPadding");
            assertThat(envelope.encoding()).isEqualTo("base64url");
            assertThat(envelope.ciphertext()).isNotEqualTo(plaintext);
            assertThat(capabilities.configurationCipher(cipher).open(envelope, context))
                    .isEqualTo(plaintext);
            assertThat(ConfigurationCipherCapability.class.getMethods())
                    .noneMatch(method -> method.getReturnType().getSimpleName().contains("SecretKey"));
        }
    }

    @Test
    void usesFreshDataKeyNonceAndCiphertextForEverySeal() throws Exception {
        KeyMaterialDescriptor cipher = descriptor(
                "configuration-v1",
                KeyMaterialPurpose.CONFIGURATION_CIPHER,
                KeyMaterialAlgorithm.AES,
                1,
                CLUSTER);
        try (KeyMaterialService service = service()) {
            service.generateSecretKeyIfMissing(cipher, 256);
            ConfigurationCipherCapability capability = KeyMaterialCapabilities
                    .open(service, List.of(cipher))
                    .configurationCipher(cipher);
            byte[] plaintext = "same-password".getBytes(StandardCharsets.UTF_8);

            ConfigurationSecretEnvelope first = capability.seal(plaintext, context());
            ConfigurationSecretEnvelope second = capability.seal(plaintext, context());

            assertThat(first.wrappedDataKey()).isNotEqualTo(second.wrappedDataKey());
            assertThat(first.nonce()).isNotEqualTo(second.nonce());
            assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
            assertThat(capability.open(first, context())).isEqualTo(plaintext);
            assertThat(capability.open(second, context())).isEqualTo(plaintext);
        }
    }

    @Test
    void rejectsEveryChangedContextWithTypedAuthenticationFailure() throws Exception {
        KeyMaterialDescriptor cipher = descriptor(
                "configuration-v1",
                KeyMaterialPurpose.CONFIGURATION_CIPHER,
                KeyMaterialAlgorithm.AES,
                1,
                CLUSTER);
        try (KeyMaterialService service = service()) {
            service.generateSecretKeyIfMissing(cipher, 256);
            ConfigurationCipherCapability capability = KeyMaterialCapabilities
                    .open(service, List.of(cipher))
                    .configurationCipher(cipher);
            ConfigurationSecretEnvelope envelope = capability.seal(
                    "password".getBytes(StandardCharsets.UTF_8), context());
            List<ConfigurationSecretContext> changed = List.of(
                    new ConfigurationSecretContext("other", "access-token"),
                    new ConfigurationSecretContext("github-token", "other"));

            for (ConfigurationSecretContext candidate : changed) {
                assertSecretFailure(
                        () -> capability.open(envelope, candidate),
                        ConfigurationSecretException.Reason.AUTHENTICATION_FAILED);
            }
        }
    }

    @Test
    void rejectsTamperedEnvelopeAndMismatchedMaterialWithoutSecretValues() throws Exception {
        KeyMaterialDescriptor cipher = descriptor(
                "configuration-v1",
                KeyMaterialPurpose.CONFIGURATION_CIPHER,
                KeyMaterialAlgorithm.AES,
                1,
                CLUSTER);
        try (KeyMaterialService service = service()) {
            service.generateSecretKeyIfMissing(cipher, 256);
            ConfigurationCipherCapability capability = KeyMaterialCapabilities
                    .open(service, List.of(cipher))
                    .configurationCipher(cipher);
            ConfigurationSecretEnvelope envelope = capability.seal(
                    "do-not-report".getBytes(StandardCharsets.UTF_8), context());

            assertSecretFailure(
                    () -> capability.open(
                            withWrappedKey(envelope, changed(envelope.wrappedDataKey())),
                            context()),
                    ConfigurationSecretException.Reason.AUTHENTICATION_FAILED);
            assertSecretFailure(
                    () -> capability.open(withNonce(envelope, changed(envelope.nonce())), context()),
                    ConfigurationSecretException.Reason.AUTHENTICATION_FAILED);
            assertSecretFailure(
                    () -> capability.open(withCiphertext(envelope, changed(envelope.ciphertext())), context()),
                    ConfigurationSecretException.Reason.AUTHENTICATION_FAILED);
            assertSecretFailure(
                    () -> capability.open(
                            withAlias(envelope, new KeyMaterialAlias("configuration-v2")),
                            context()),
                    ConfigurationSecretException.Reason.MATERIAL_MISMATCH);
            assertSecretFailure(
                    () -> capability.open(withVersion(envelope, new KeyMaterialVersion(2)), context()),
                    ConfigurationSecretException.Reason.MATERIAL_MISMATCH);
        }
    }

    @Test
    void rejectsPurposeSpecificCapabilityMismatch() throws Exception {
        try (KeyMaterialService service = service()) {
            KeyMaterialDescriptor ssh = descriptor(
                    "ssh-host-v1", KeyMaterialPurpose.SSH_HOST, KeyMaterialAlgorithm.RSA, 1, NODE);
            service.generateKeyIfMissing(ssh, 2048);
            KeyMaterialCapabilities capabilities = KeyMaterialCapabilities.open(service, List.of(ssh));

            assertThatThrownBy(() -> capabilities.signing(ssh))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SERVER_SIGNING");
        }
    }

    @Test
    void rejectsMixedPurposeVerificationMaterial() throws Exception {
        try (KeyMaterialService service = service()) {
            KeyMaterialDescriptor signing = descriptor(
                    "server-signing-v1",
                    KeyMaterialPurpose.SERVER_SIGNING,
                    KeyMaterialAlgorithm.RSA,
                    1,
                    CLUSTER);
            KeyMaterialDescriptor ca = descriptor(
                    "orion-ca-v1",
                    KeyMaterialPurpose.CERTIFICATE_AUTHORITY,
                    KeyMaterialAlgorithm.RSA,
                    1,
                    CLUSTER);
            service.generateKeyIfMissing(signing, 2048);
            service.generateKeyIfMissing(ca, 2048);
            KeyMaterialCapabilities capabilities = KeyMaterialCapabilities.open(service, List.of(signing, ca));

            assertThatThrownBy(() -> capabilities.verification(List.of(signing, ca)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("purpose");
        }
    }

    private static KeyMaterialService service() throws Exception {
        return KeyMaterialService.open(new InMemoryKeyMaterialContentStore(), options(true));
    }

    private static ConfigurationSecretContext context() {
        return new ConfigurationSecretContext("github-token", "access-token");
    }

    private static void assertSecretFailure(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
            ConfigurationSecretException.Reason reason) {
        assertThatThrownBy(operation)
                .isInstanceOfSatisfying(
                        ConfigurationSecretException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(reason))
                .hasMessageNotContaining("do-not-report")
                .hasMessageNotContaining("password");
    }

    private static ConfigurationSecretEnvelope withWrappedKey(
            ConfigurationSecretEnvelope envelope,
            byte[] wrappedDataKey) {
        return copy(envelope, envelope.keyAlias(), envelope.keyVersion(), wrappedDataKey,
                envelope.nonce(), envelope.ciphertext());
    }

    private static ConfigurationSecretEnvelope withNonce(
            ConfigurationSecretEnvelope envelope,
            byte[] nonce) {
        return copy(envelope, envelope.keyAlias(), envelope.keyVersion(), envelope.wrappedDataKey(),
                nonce, envelope.ciphertext());
    }

    private static ConfigurationSecretEnvelope withCiphertext(
            ConfigurationSecretEnvelope envelope,
            byte[] ciphertext) {
        return copy(envelope, envelope.keyAlias(), envelope.keyVersion(), envelope.wrappedDataKey(),
                envelope.nonce(), ciphertext);
    }

    private static ConfigurationSecretEnvelope withAlias(
            ConfigurationSecretEnvelope envelope,
            KeyMaterialAlias alias) {
        return copy(envelope, alias, envelope.keyVersion(), envelope.wrappedDataKey(),
                envelope.nonce(), envelope.ciphertext());
    }

    private static ConfigurationSecretEnvelope withVersion(
            ConfigurationSecretEnvelope envelope,
            KeyMaterialVersion version) {
        return copy(envelope, envelope.keyAlias(), version, envelope.wrappedDataKey(),
                envelope.nonce(), envelope.ciphertext());
    }

    private static ConfigurationSecretEnvelope copy(
            ConfigurationSecretEnvelope envelope,
            KeyMaterialAlias alias,
            KeyMaterialVersion version,
            byte[] wrappedDataKey,
            byte[] nonce,
            byte[] ciphertext) {
        return new ConfigurationSecretEnvelope(
                envelope.version(),
                alias,
                version,
                envelope.wrappingAlgorithm(),
                envelope.encryptionAlgorithm(),
                envelope.encoding(),
                wrappedDataKey,
                nonce,
                ciphertext);
    }

    private static byte[] changed(byte[] value) {
        byte[] changed = Arrays.copyOf(value, value.length);
        changed[changed.length - 1] ^= 1;
        return changed;
    }

    private static KeyMaterialOptions options(boolean createIfMissing) {
        return KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password(), createIfMissing);
    }

    private static KeyMaterialDescriptor descriptor(
            String alias,
            KeyMaterialPurpose purpose,
            KeyMaterialAlgorithm algorithm,
            long version,
            KeyMaterialScope scope) {
        return new KeyMaterialDescriptor(
                new KeyMaterialAlias(alias), purpose, algorithm, new KeyMaterialVersion(version), scope);
    }
}
