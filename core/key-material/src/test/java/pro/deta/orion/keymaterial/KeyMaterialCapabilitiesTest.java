package pro.deta.orion.keymaterial;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
        try (KeyMaterialService initial = KeyMaterialService.open(store, options())) {
            initial.generateKeyIfMissing(signing, 0);
            initial.save();
        }

        try (KeyMaterialService reloaded = KeyMaterialService.open(store, options())) {
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
        try (KeyMaterialService initial = KeyMaterialService.open(store, options())) {
            generatedRsa = initial.generateKeyIfMissing(rsaClient, 2048);
            generatedEd25519 = initial.generateKeyIfMissing(ed25519Client, 0);
            initial.generateKeyIfMissing(host, 2048);
            initial.save();
        }

        try (KeyMaterialService reloaded = KeyMaterialService.open(store, options())) {
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
        try (KeyMaterialService initial = KeyMaterialService.open(store, options())) {
            initial.generateKeyIfMissing(original, 2048);
            initial.save();
        }

        try (KeyMaterialService reloaded = KeyMaterialService.open(store, options())) {
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
        try (KeyMaterialService initial = KeyMaterialService.open(store, options())) {
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
        try (KeyMaterialService reloaded = KeyMaterialService.open(store, options())) {
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

            SSLContext context = capabilities.tls(
                    tls,
                    Optional.empty(),
                    List.of(),
                    TlsClientAuthentication.DISABLED).createContext();
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
    void lazilyCreatesAndDurablyReusesAcmeKeysTogether() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialDescriptor account = descriptor(
                "acme-account-v1", KeyMaterialPurpose.ACME_ACCOUNT, KeyMaterialAlgorithm.RSA, 1, CLUSTER);
        KeyMaterialDescriptor identity = descriptor(
                "https-identity-v1", KeyMaterialPurpose.TLS_IDENTITY, KeyMaterialAlgorithm.RSA, 1, CLUSTER);
        byte[] accountPublic;
        byte[] identityPublic;
        try (KeyMaterialService service = KeyMaterialService.open(store, options())) {
            SelectedAcmeKeyMaterial capability = KeyMaterialCapabilities
                    .open(service, List.of(account, identity))
                    .acme(account, identity, Optional.empty());

            AcmeKeyMaterial material = capability.acquire(2048, 2048);
            accountPublic = material.accountKeyPair().getPublic().getEncoded();
            identityPublic = material.domainKeyPair().getPublic().getEncoded();

            assertThat(store.read().orElseThrow().version()).isEqualTo("1");
            assertThat(capability.acquire(2048, 2048).accountKeyPair().getPublic().getEncoded())
                    .isEqualTo(accountPublic);
            assertThat(store.read().orElseThrow().version()).isEqualTo("1");
        }

        try (KeyMaterialService service = KeyMaterialService.open(store, options())) {
            AcmeKeyMaterial material = KeyMaterialCapabilities
                    .open(service, List.of(account, identity))
                    .acme(account, identity, Optional.empty())
                    .acquire(2048, 2048);

            assertThat(material.accountKeyPair().getPublic().getEncoded()).isEqualTo(accountPublic);
            assertThat(material.domainKeyPair().getPublic().getEncoded()).isEqualTo(identityPublic);
            assertThat(store.read().orElseThrow().version()).isEqualTo("1");
        }
    }

    @Test
    void doesNotDurablySaveOnlyOneAcmeKeyWhenGenerationFails() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialDescriptor account = descriptor(
                "acme-account-v1", KeyMaterialPurpose.ACME_ACCOUNT, KeyMaterialAlgorithm.RSA, 1, CLUSTER);
        KeyMaterialDescriptor identity = descriptor(
                "https-identity-v1", KeyMaterialPurpose.TLS_IDENTITY, KeyMaterialAlgorithm.EC, 1, CLUSTER);
        try (KeyMaterialService service = KeyMaterialService.open(store, options())) {
            SelectedAcmeKeyMaterial capability = KeyMaterialCapabilities
                    .open(service, List.of(account, identity))
                    .acme(account, identity, Optional.empty());

            assertThatThrownBy(() -> capability.acquire(2048, 2048))
                    .isInstanceOf(Exception.class);
            assertThat(store.read()).isEmpty();
        }
    }

    @Test
    void rejectsNonAcmeAndNonTlsDescriptorsForAcmeCapability() throws Exception {
        try (KeyMaterialService service = service()) {
            KeyMaterialDescriptor account = descriptor(
                    "acme-account-v1", KeyMaterialPurpose.ACME_ACCOUNT, KeyMaterialAlgorithm.RSA, 1, CLUSTER);
            KeyMaterialDescriptor signing = descriptor(
                    "signing-v1", KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialAlgorithm.RSA, 1, CLUSTER);
            KeyMaterialDescriptor identity = descriptor(
                    "https-v1", KeyMaterialPurpose.TLS_IDENTITY, KeyMaterialAlgorithm.RSA, 1, CLUSTER);
            service.generateKeyIfMissing(signing, 2048);
            KeyMaterialCapabilities capabilities = KeyMaterialCapabilities.open(
                    service, List.of(account, signing, identity));

            assertThatThrownBy(() -> capabilities.acme(signing, identity, Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ACME_ACCOUNT");
            assertThatThrownBy(() -> capabilities.acme(account, signing, Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("TLS_IDENTITY");
        }
    }

    @Test
    void validatesInstallsAndReloadsAcmeCertificateChainWithoutRootInIdentity() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialDescriptor account = descriptor(
                "acme-account-v1", KeyMaterialPurpose.ACME_ACCOUNT, KeyMaterialAlgorithm.RSA, 1, CLUSTER);
        KeyMaterialDescriptor identity = descriptor(
                "https-identity-v1", KeyMaterialPurpose.TLS_IDENTITY, KeyMaterialAlgorithm.RSA, 1, CLUSTER);
        TrustedCertificateDescriptor rootDescriptor = trustedCertificate("public-root-v1");
        TestCertificateChain.Authority root = TestCertificateChain.root("Public Root");
        TestCertificateChain.Authority intermediate = TestCertificateChain.intermediate("Intermediate", root);

        try (KeyMaterialService service = KeyMaterialService.open(store, options())) {
            SelectedAcmeKeyMaterial capability = KeyMaterialCapabilities
                    .open(service, List.of(account, identity), List.of(rootDescriptor))
                    .acme(account, identity, Optional.of(rootDescriptor));
            KeyPair domainKey = capability.acquire(2048, 2048).domainKeyPair();
            X509Certificate leaf = TestCertificateChain.leaf("orion.example", domainKey, intermediate);

            capability.installCertificateChain(
                    List.of(leaf, intermediate.certificate()),
                    Optional.of(root.certificate()));

            assertThat(store.read().orElseThrow().version()).isEqualTo("2");
            assertThat(capability.certificateChain()).containsExactly(leaf, intermediate.certificate());
            assertThat(capability.issuerTrustAnchor()).contains(root.certificate());
        }

        try (KeyMaterialService service = KeyMaterialService.open(store, options())) {
            KeyMaterialCapabilities capabilities = KeyMaterialCapabilities.open(
                    service, List.of(account, identity), List.of(rootDescriptor));
            SelectedAcmeKeyMaterial capability = capabilities.acme(
                    account, identity, Optional.of(rootDescriptor));
            SelectedTlsMaterial tls = capabilities.tls(
                    identity,
                    Optional.of(rootDescriptor),
                    List.of(),
                    TlsClientAuthentication.DISABLED);

            assertThat(capability.certificateChain()).hasSize(2);
            assertThat(tls.certificateChain()).hasSize(2);
            assertThat(tls.serverIssuerTrustAnchor()).contains(root.certificate());
            assertThat(tls.createContext().getProtocol()).isEqualTo("TLS");
        }
    }

    @Test
    void rejectsInvalidAcmeCertificateChainsBeforeSaving() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialDescriptor account = descriptor(
                "acme-account-v1", KeyMaterialPurpose.ACME_ACCOUNT, KeyMaterialAlgorithm.RSA, 1, CLUSTER);
        KeyMaterialDescriptor identity = descriptor(
                "https-identity-v1", KeyMaterialPurpose.TLS_IDENTITY, KeyMaterialAlgorithm.RSA, 1, CLUSTER);
        TrustedCertificateDescriptor rootDescriptor = trustedCertificate("public-root-v1");
        TestCertificateChain.Authority root = TestCertificateChain.root("Public Root");
        TestCertificateChain.Authority otherRoot = TestCertificateChain.root("Other Root");
        TestCertificateChain.Authority intermediate = TestCertificateChain.intermediate("Intermediate", root);
        try (KeyMaterialService service = KeyMaterialService.open(store, options())) {
            SelectedAcmeKeyMaterial capability = KeyMaterialCapabilities
                    .open(service, List.of(account, identity), List.of(rootDescriptor))
                    .acme(account, identity, Optional.of(rootDescriptor));
            KeyPair domainKey = capability.acquire(2048, 2048).domainKeyPair();
            KeyPair otherKey = KeyPairGenerator.getInstance("RSA").generateKeyPair();
            X509Certificate validLeaf = TestCertificateChain.leaf("orion.example", domainKey, intermediate);
            X509Certificate wrongLeaf = TestCertificateChain.leaf("other.example", otherKey, intermediate);
            TestCertificateChain.Authority unrelatedIntermediate =
                    TestCertificateChain.intermediate("Unrelated", otherRoot);

            assertThatThrownBy(() -> capability.installCertificateChain(
                    List.of(wrongLeaf, intermediate.certificate()), Optional.of(root.certificate())))
                    .hasMessageContaining("public key");
            assertThatThrownBy(() -> capability.installCertificateChain(
                    List.of(validLeaf, unrelatedIntermediate.certificate()),
                    Optional.of(otherRoot.certificate())))
                    .hasMessageContaining("signature");
            assertThatThrownBy(() -> capability.installCertificateChain(
                    List.of(validLeaf, intermediate.certificate()), Optional.of(otherRoot.certificate())))
                    .hasMessageContaining("issuer root");
            assertThatThrownBy(() -> capability.installCertificateChain(
                    List.of(validLeaf, intermediate.certificate(), root.certificate()),
                    Optional.of(root.certificate())))
                    .hasMessageContaining("must not include")
                    .hasMessageContaining("root");
            assertThatThrownBy(() -> capability.installCertificateChain(
                    List.of(nonX509Certificate(), intermediate.certificate()),
                    Optional.of(root.certificate())))
                    .hasMessageContaining("X.509");
            assertThat(store.read().orElseThrow().version()).isEqualTo("1");
        }
    }

    @Test
    void keepsServerIssuerAndClientTrustRolesSeparate() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialDescriptor identity = descriptor(
                "https-v1", KeyMaterialPurpose.TLS_IDENTITY, KeyMaterialAlgorithm.RSA, 1, CLUSTER);
        TrustedCertificateDescriptor serverRoot = trustedCertificate("server-root-v1");
        TrustedCertificateDescriptor clientRoot = trustedCertificate("client-root-v1");
        TestCertificateChain.Authority serverAuthority = TestCertificateChain.root("Server Root");
        TestCertificateChain.Authority clientAuthority = TestCertificateChain.root("Client Root");
        KeyPair trustedClientKey = TestCertificateChain.keyPair();
        X509Certificate trustedClientCertificate = TestCertificateChain.leaf(
                "trusted-client", trustedClientKey, clientAuthority);
        KeyPair issuerSignedClientKey = TestCertificateChain.keyPair();
        X509Certificate issuerSignedClientCertificate = TestCertificateChain.leaf(
                "issuer-signed-client", issuerSignedClientKey, serverAuthority);
        try (KeyMaterialService service = KeyMaterialService.open(store, options())) {
            KeyPair domainKey = service.generateKeyIfMissing(identity, 2048);
            service.setPrivateKey(
                    identity,
                    domainKey,
                    List.of(TestCertificateChain.leaf("orion.example", domainKey, serverAuthority)));
            service.setTrustedCertificate(serverRoot, serverAuthority.certificate());
            service.setTrustedCertificate(clientRoot, clientAuthority.certificate());
            service.save();
            KeyMaterialCapabilities capabilities = KeyMaterialCapabilities.open(
                    service, List.of(identity), List.of(serverRoot, clientRoot));

            SelectedTlsMaterial disabled = capabilities.tls(
                    identity,
                    Optional.of(serverRoot),
                    List.of(),
                    TlsClientAuthentication.DISABLED);
            SelectedTlsMaterial wanted = capabilities.tls(
                    identity,
                    Optional.of(serverRoot),
                    List.of(clientRoot),
                    TlsClientAuthentication.WANT);
            SelectedTlsMaterial required = capabilities.tls(
                    identity,
                    Optional.of(serverRoot),
                    List.of(clientRoot),
                    TlsClientAuthentication.REQUIRED);
            SSLContext trustedClient = clientContext(
                    serverAuthority.certificate(), trustedClientKey, trustedClientCertificate);
            SSLContext issuerSignedClient = clientContext(
                    serverAuthority.certificate(), issuerSignedClientKey, issuerSignedClientCertificate);

            assertThat(required.clientAuthentication()).isEqualTo(TlsClientAuthentication.REQUIRED);
            assertThat(required.serverIssuerTrustAnchor()).contains(serverAuthority.certificate());
            assertThat(handshake(disabled, issuerSignedClient)).isEqualTo(new HandshakeResult(true, false));
            assertThat(handshake(wanted, trustedClient)).isEqualTo(new HandshakeResult(true, true));
            assertThat(handshake(required, trustedClient)).isEqualTo(new HandshakeResult(true, true));
            assertThat(handshake(required, issuerSignedClient).successful()).isFalse();

            SelectedTlsMaterial sharedRootRoles = capabilities.tls(
                    identity,
                    Optional.of(serverRoot),
                    List.of(clientRoot, serverRoot),
                    TlsClientAuthentication.REQUIRED);
            assertThat(handshake(sharedRootRoles, issuerSignedClient))
                    .isEqualTo(new HandshakeResult(true, true));
            assertThatThrownBy(() -> capabilities.tls(
                    identity,
                    Optional.of(serverRoot),
                    List.of(),
                    TlsClientAuthentication.REQUIRED))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("client trust");
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
        try (KeyMaterialService service = KeyMaterialService.open(store, options())) {
            service.generateSecretKeyIfMissing(cipher, 256);
            service.save();
            KeyMaterialCapabilities capabilities = KeyMaterialCapabilities.open(service, List.of(cipher));

            ConfigurationSecretEnvelope envelope = capabilities
                    .configurationCipher(cipher)
                    .seal(plaintext, context);
            serializedEnvelope = codec.serialize(envelope);
        }

        try (KeyMaterialService reloaded = KeyMaterialService.open(store, options())) {
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
        return KeyMaterialService.open(new InMemoryKeyMaterialContentStore(), options());
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

    private static KeyMaterialOptions options() {
        return KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password());
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

    private static TrustedCertificateDescriptor trustedCertificate(String alias) {
        return new TrustedCertificateDescriptor(
                new KeyMaterialAlias(alias), KeyMaterialAlgorithm.RSA, new KeyMaterialVersion(1), CLUSTER);
    }

    private static Certificate nonX509Certificate() {
        return new Certificate("test") {
            @Override
            public byte[] getEncoded() throws CertificateEncodingException {
                return new byte[]{1};
            }

            @Override
            public void verify(java.security.PublicKey key) {
            }

            @Override
            public void verify(java.security.PublicKey key, String sigProvider) {
            }

            @Override
            public String toString() {
                return "non-X.509";
            }

            @Override
            public java.security.PublicKey getPublicKey() {
                return null;
            }
        };
    }

    private static SSLContext clientContext(
            X509Certificate serverRoot,
            KeyPair clientKey,
            X509Certificate clientCertificate) throws Exception {
        char[] password = "test-password".toCharArray();
        KeyStore keys = KeyStore.getInstance(KeyMaterialConstants.DEFAULT_KEY_STORE_TYPE);
        keys.load(null, password);
        keys.setKeyEntry(
                "client",
                clientKey.getPrivate(),
                password,
                new Certificate[]{clientCertificate});
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keys, password);

        KeyStore trust = KeyStore.getInstance(KeyMaterialConstants.DEFAULT_KEY_STORE_TYPE);
        trust.load(null, password);
        trust.setCertificateEntry("server-root", serverRoot);
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trust);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(
                keyManagerFactory.getKeyManagers(),
                trustManagerFactory.getTrustManagers(),
                new SecureRandom());
        return context;
    }

    private static HandshakeResult handshake(
            SelectedTlsMaterial capability,
            SSLContext clientContext) throws Exception {
        try (SSLServerSocket serverSocket = (SSLServerSocket) capability
                .createContext()
                .getServerSocketFactory()
                .createServerSocket(0);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            configureClientAuthentication(serverSocket, capability.clientAuthentication());
            Future<ServerHandshakeResult> server = executor.submit(() -> acceptHandshake(serverSocket));
            boolean clientSucceeded = clientHandshake(clientContext, serverSocket.getLocalPort());
            ServerHandshakeResult serverResult;
            try {
                serverResult = server.get(5, TimeUnit.SECONDS);
            } catch (ExecutionException failure) {
                serverResult = new ServerHandshakeResult(false, false);
            }
            return new HandshakeResult(
                    clientSucceeded && serverResult.successful(),
                    serverResult.observedClient());
        }
    }

    private static void configureClientAuthentication(
            SSLServerSocket socket,
            TlsClientAuthentication clientAuthentication) {
        switch (clientAuthentication) {
            case DISABLED -> {
                socket.setNeedClientAuth(false);
                socket.setWantClientAuth(false);
            }
            case WANT -> socket.setWantClientAuth(true);
            case REQUIRED -> socket.setNeedClientAuth(true);
        }
    }

    private static ServerHandshakeResult acceptHandshake(SSLServerSocket serverSocket) throws Exception {
        try (SSLSocket socket = (SSLSocket) serverSocket.accept()) {
            socket.setSoTimeout(3_000);
            socket.startHandshake();
            try {
                return new ServerHandshakeResult(
                        true, socket.getSession().getPeerCertificates().length > 0);
            } catch (javax.net.ssl.SSLPeerUnverifiedException ignored) {
                return new ServerHandshakeResult(true, false);
            }
        }
    }

    private static boolean clientHandshake(SSLContext context, int port) {
        try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket("127.0.0.1", port)) {
            socket.setSoTimeout(3_000);
            socket.startHandshake();
            return true;
        } catch (Exception failure) {
            return false;
        }
    }

    private record HandshakeResult(boolean successful, boolean serverObservedClient) {
    }

    private record ServerHandshakeResult(boolean successful, boolean observedClient) {
    }
}
