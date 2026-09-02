package pro.deta.orion.keymaterial;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
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
    void encryptsConfigurationWithSymmetricMaterialKeptBehindCapability() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialDescriptor cipher = descriptor(
                "configuration-v1",
                KeyMaterialPurpose.CONFIGURATION_CIPHER,
                KeyMaterialAlgorithm.AES,
                1,
                CLUSTER);
        try (KeyMaterialService service = KeyMaterialService.open(store, options(true))) {
            service.generateSecretKeyIfMissing(cipher, 256);
            service.save();
        }

        try (KeyMaterialService reloaded = KeyMaterialService.open(store, options(false))) {
            KeyMaterialCapabilities capabilities = KeyMaterialCapabilities.open(reloaded, List.of(cipher));
            byte[] plaintext = "database-password".getBytes(StandardCharsets.UTF_8);

            EncryptedConfigurationValue encrypted = capabilities.configurationCipher(cipher).encrypt(plaintext);

            assertThat(encrypted.ciphertext()).isNotEqualTo(plaintext);
            assertThat(capabilities.configurationCipher(cipher).decrypt(encrypted)).isEqualTo(plaintext);
            assertThat(ConfigurationCipherCapability.class.getMethods())
                    .noneMatch(method -> method.getReturnType().getSimpleName().contains("SecretKey"));
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
