package pro.deta.orion.keymaterial;

import pro.deta.orion.storage.InMemoryBlobStorage;

import pro.deta.orion.event.OrionEventManager;

import java.util.Set;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.GeneralSecurityException;
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

class KeyMaterialOperationsTest {
    @Test
    void reusesStoredKeyForItsRequestedPurposesWithoutGrantingMissingPurposes() throws Exception {
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        KeyMaterialId stored = new KeyMaterialId("shared", 1);
        KeyMaterialId signing = new KeyMaterialId("shared", 1);
        KeyMaterialId host = new KeyMaterialId("shared", 1);
        try (OrionKeyMaterial service = OrionKeyMaterial.open(new KeyMaterialRepository(
                store,
                new KeyMaterialCodec(options()),
                new OrionEventManager()))) {
            KeyMaterialModification modification = service.modify(service.revision());
            modification.generateKey("shared", Set.of(KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialPurpose.SSH_HOST), KeyMaterialAlgorithm.RSA, 2048);
            modification.apply();

            byte[] payload = new byte[]{1, 2, 3};
            byte[] signature = service.sign(signing, payload);
            assertThat(service.verify(signing, payload, signature)).isTrue();
            service.require(host, Set.of(KeyMaterialPurpose.SSH_HOST), KeyMaterialAlgorithm.RSA);
            KeyMaterialId missingRole = new KeyMaterialId("shared", 1);
            assertThatThrownBy(() -> service.require(missingRole, Set.of(KeyMaterialPurpose.TLS_IDENTITY), KeyMaterialAlgorithm.RSA))
                    .isInstanceOf(GeneralSecurityException.class).hasMessageContaining("purpose");
        }
    }

    @Test
    void signsAndVerifiesWithoutExposingPrivateKey() throws Exception {
        try (OrionKeyMaterial service = service()) {
            KeyMaterialId signing = new KeyMaterialId("server-signing-v1", 1);
            generateKey(service, signing, Set.of((KeyMaterialPurpose.SERVER_SIGNING)), (KeyMaterialAlgorithm.RSA), 2048);

            byte[] payload = "short-lived-token".getBytes(StandardCharsets.UTF_8);
            byte[] signature = service.sign(signing, payload);

            assertThat(service.verify(signing, payload, signature)).isTrue();
            assertThat(service.verify(signing, 
                    "modified".getBytes(StandardCharsets.UTF_8), signature)).isFalse();
        }
    }

    @Test
    void reloadsAndUsesGeneratedEd25519SigningMaterial() throws Exception {
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        KeyMaterialId signing = new KeyMaterialId("server-signing-ed25519-v1", 1);
        try (OrionKeyMaterial initial = OrionKeyMaterial.open(new KeyMaterialRepository(
                store,
                new KeyMaterialCodec(options()),
                new OrionEventManager()))) {
            generateKey(initial, signing, Set.of((KeyMaterialPurpose.SERVER_SIGNING)), (KeyMaterialAlgorithm.ED25519), 0);
        }

        try (OrionKeyMaterial reloaded = OrionKeyMaterial.open(new KeyMaterialRepository(
                store,
                new KeyMaterialCodec(options()),
                new OrionEventManager()))) {

            byte[] payload = "ed25519-payload".getBytes(StandardCharsets.UTF_8);
            byte[] signature = reloaded.sign(signing, payload);

            assertThat(reloaded.verify(signing, payload, signature)).isTrue();
        }
    }

    @Test
    void reloadsSshClientKeyMaterialAndRejectsWrongDescriptorMetadata() throws Exception {
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        KeyMaterialId rsaClient = new KeyMaterialId("provisioning-client-v1", 1);
        KeyMaterialId ed25519Client = new KeyMaterialId("provisioning-client-ed25519-v1", 1);
        KeyMaterialId host = new KeyMaterialId("provisioning-host-v1", 1);
        KeyPair generatedRsa;
        KeyPair generatedEd25519;
        try (OrionKeyMaterial initial = OrionKeyMaterial.open(new KeyMaterialRepository(
                store,
                new KeyMaterialCodec(options()),
                new OrionEventManager()))) {
            generatedRsa = generateKey(initial, rsaClient, Set.of((KeyMaterialPurpose.SSH_CLIENT)), (KeyMaterialAlgorithm.RSA), 2048);
            generatedEd25519 = generateKey(initial, ed25519Client, Set.of((KeyMaterialPurpose.SSH_CLIENT)), (KeyMaterialAlgorithm.ED25519), 0);
            generateKey(initial, host, Set.of((KeyMaterialPurpose.SSH_HOST)), (KeyMaterialAlgorithm.RSA), 2048);
        }

        try (OrionKeyMaterial reloaded = OrionKeyMaterial.open(new KeyMaterialRepository(
                store,
                new KeyMaterialCodec(options()),
                new OrionEventManager()))) {

            KeyPair selectedRsa = reloaded.read(rsaClient).keyPair();
            KeyPair selectedEd25519 = reloaded.read(ed25519Client).keyPair();

            assertThat(selectedRsa.getPublic().getEncoded())
                    .isEqualTo(generatedRsa.getPublic().getEncoded());
            assertThat(selectedRsa.getPrivate().getEncoded())
                    .isEqualTo(generatedRsa.getPrivate().getEncoded());
            assertThat(selectedEd25519.getPublic().getEncoded())
                    .isEqualTo(generatedEd25519.getPublic().getEncoded());
            assertThat(selectedEd25519.getPrivate().getEncoded())
                    .isEqualTo(generatedEd25519.getPrivate().getEncoded());

            KeyMaterialId wrongPurpose = new KeyMaterialId((rsaClient.alias()), (rsaClient.version()));
            KeyMaterialId wrongAlgorithm = new KeyMaterialId(rsaClient.alias(), rsaClient.version());

            assertThatThrownBy(() -> reloaded.require(wrongPurpose, Set.of((KeyMaterialPurpose.SSH_HOST)), ((KeyMaterialAlgorithm.RSA))))
                    .isInstanceOf(GeneralSecurityException.class)
                    .hasMessageContaining("purpose");
            assertThatThrownBy(() -> reloaded.require(wrongAlgorithm, Set.of((KeyMaterialPurpose.SSH_CLIENT)), KeyMaterialAlgorithm.EC))
                    .isInstanceOf(GeneralSecurityException.class)
                    .hasMessageContaining("algorithm");
            assertThat(reloaded.read(host).keyPair().getPublic().getAlgorithm()).isEqualTo("RSA");

        }
    }

    @Test
    void validatesExistingEntriesBeforeReuseOrActivation() throws Exception {
        try (OrionKeyMaterial service = service()) {
            KeyMaterialId rsa = new KeyMaterialId("host-key-v1", 1);
            generateKey(service, rsa, Set.of((KeyMaterialPurpose.SSH_HOST)), (KeyMaterialAlgorithm.RSA), 2048);
            KeyMaterialId incompatible = new KeyMaterialId("host-key-v1", 1);

            assertThatThrownBy(() -> generateKey(service, incompatible, Set.of((KeyMaterialPurpose.SSH_HOST)), (KeyMaterialAlgorithm.EC), 256))
                    .isInstanceOf(Exception.class)
                    .hasMessageContaining("algorithm")
                    .hasMessageContaining("host-key-v1");
            assertThatThrownBy(() -> service.require(incompatible, Set.of((KeyMaterialPurpose.SSH_HOST)), (KeyMaterialAlgorithm.EC)))
                    .isInstanceOf(Exception.class)
                    .hasMessageContaining("algorithm")
                    .hasMessageContaining("host-key-v1");
        }
    }

    @Test
    void rejectsReusingExistingAliasWithDifferentVersionAfterReload() throws Exception {
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        KeyMaterialId original = new KeyMaterialId("server-signing-v1", 1);
        try (OrionKeyMaterial initial = OrionKeyMaterial.open(new KeyMaterialRepository(
                store,
                new KeyMaterialCodec(options()),
                new OrionEventManager()))) {
            generateKey(initial, original, Set.of((KeyMaterialPurpose.SERVER_SIGNING)), (KeyMaterialAlgorithm.RSA), 2048);
        }

        try (OrionKeyMaterial reloaded = OrionKeyMaterial.open(new KeyMaterialRepository(
                store,
                new KeyMaterialCodec(options()),
                new OrionEventManager()))) {
            KeyMaterialId changedVersion = new KeyMaterialId("server-signing-v1", 2);

            assertThatThrownBy(() -> generateKey(reloaded, changedVersion, Set.of((KeyMaterialPurpose.SERVER_SIGNING)), (KeyMaterialAlgorithm.RSA), 2048))
                    .isInstanceOf(Exception.class)
                    .hasMessageContaining("version");
        }
    }

    @Test
    void exposesTlsSshAndCaThroughDirectMaterialOperations() throws Exception {
        try (OrionKeyMaterial service = service()) {
            KeyMaterialId tls = new KeyMaterialId("https-v1", 1);
            KeyMaterialId ssh = new KeyMaterialId("ssh-host-v1", 1);
            KeyMaterialId ca = new KeyMaterialId("orion-ca-v1", 1);
            generateKey(service, tls, Set.of((KeyMaterialPurpose.TLS_IDENTITY)), (KeyMaterialAlgorithm.RSA), 2048);
            generateKey(service, ssh, Set.of((KeyMaterialPurpose.SSH_HOST)), (KeyMaterialAlgorithm.RSA), 2048);
            generateKey(service, ca, Set.of((KeyMaterialPurpose.CERTIFICATE_AUTHORITY)), (KeyMaterialAlgorithm.RSA), 2048);

            TestCertificateChain.Authority authority = TestCertificateChain.root("tls-root");
            X509Certificate leaf = TestCertificateChain.leaf(
                    "localhost", service.read(tls).keyPair(), authority);
            tls = installCertificateChain(service, tls, List.of(leaf), Optional.empty(), Optional.empty());
            SSLContext context = service.certificateIdentity(new TlsMaterialConfiguration(
                    tls,
                    Optional.empty(),
                    List.of(),
                    TlsClientAuthentication.DISABLED)).createContext();
            KeyPair sshHostKey = service.read(ssh).keyPair();
            byte[] payload = "certificate-body".getBytes(StandardCharsets.UTF_8);
            byte[] signature = service.sign(ca, payload);

            assertThat(context.getProtocol()).isEqualTo("TLS");
            assertThat(sshHostKey.getPrivate().getAlgorithm()).isEqualTo("RSA");
            assertThat(service.verify(ca, payload, signature)).isTrue();
            assertThat(service.read(ca).certificateChain()).hasSize(1);
        }
    }

    @Test
    void lazilyCreatesAndDurablyReusesKeysTogether() throws Exception {
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        KeyMaterialId account = new KeyMaterialId("acme-account-v1", 1);
        KeyMaterialId identity = new KeyMaterialId("https-identity-v1", 1);
        byte[] accountPublic;
        byte[] identityPublic;
        try (OrionKeyMaterial service = OrionKeyMaterial.open(new KeyMaterialRepository(
                store,
                new KeyMaterialCodec(options()),
                new OrionEventManager()))) {
            generateKeys(service, account, identity);
            accountPublic = service.read(account).keyPair().getPublic().getEncoded();
            identityPublic = service.read(identity).keyPair().getPublic().getEncoded();

            assertThat(store.load().revision()).isEqualTo("1");
            generateKeys(service, account, identity);
            assertThat(service.read(account).keyPair().getPublic().getEncoded())
                    .isEqualTo(accountPublic);
            assertThat(store.load().revision()).isEqualTo("1");
        }

        try (OrionKeyMaterial service = OrionKeyMaterial.open(new KeyMaterialRepository(
                store,
                new KeyMaterialCodec(options()),
                new OrionEventManager()))) {
            generateKeys(service, account, identity);
            assertThat(service.read(account).keyPair().getPublic().getEncoded()).isEqualTo(accountPublic);
            assertThat(service.read(identity).keyPair().getPublic().getEncoded())
                    .isEqualTo(identityPublic);
            assertThat(store.load().revision()).isEqualTo("1");
        }
    }

    @Test
    void doesNotDurablySaveOnlyOneKeyWhenGenerationFails() throws Exception {
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        KeyMaterialId account = new KeyMaterialId("acme-account-v1", 1);
        KeyMaterialId identity = new KeyMaterialId("https-identity-v1", 1);
        try (OrionKeyMaterial service = OrionKeyMaterial.open(new KeyMaterialRepository(
                store,
                new KeyMaterialCodec(options()),
                new OrionEventManager()))) {
            assertThatThrownBy(() -> generateKeys(service, account, identity))
                    .isInstanceOf(Exception.class);
            assertThat(store.load().bytes()).isNull();
        }
    }

    @Test
    void rejectsNonTlsDescriptorsForCertificateIdentity() throws Exception {
        try (OrionKeyMaterial service = service()) {
            KeyMaterialId signing = new KeyMaterialId("signing-v1", 1);
            generateKey(service, signing, Set.of((KeyMaterialPurpose.SERVER_SIGNING)), (KeyMaterialAlgorithm.RSA), 2048);

            assertThatThrownBy(() -> service.certificateIdentity(new TlsMaterialConfiguration(
                    signing, Optional.empty(), List.of(), TlsClientAuthentication.DISABLED)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("TLS_IDENTITY");
        }
    }

    @Test
    void validatesInstallsAndReloadsCertificateChainWithoutRootInIdentity() throws Exception {
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        KeyMaterialId identity = new KeyMaterialId("https-identity-v1", 1);
        KeyMaterialId rootDescriptor = new KeyMaterialId("public-root-v1", 1);
        TestCertificateChain.Authority root = TestCertificateChain.root("Public Root");
        TestCertificateChain.Authority intermediate = TestCertificateChain.intermediate("Intermediate", root);

        try (OrionKeyMaterial service = OrionKeyMaterial.open(new KeyMaterialRepository(
                store,
                new KeyMaterialCodec(options()),
                new OrionEventManager()))) {

            CertificateIdentity certificate = service.certificateIdentity(new TlsMaterialConfiguration(identity,
                    Optional.of(rootDescriptor), List.of(), TlsClientAuthentication.DISABLED));
            generateKey(service, identity, Set.of((KeyMaterialPurpose.TLS_IDENTITY)), (KeyMaterialAlgorithm.RSA), 2048);
            KeyPair domainKey = service.read(identity).keyPair();
            X509Certificate leaf = TestCertificateChain.leaf("orion.example", domainKey, intermediate);

            certificate = certificate.installCertificateChain(
                    List.of(leaf, intermediate.certificate()),
                    Optional.of(root.certificate()));

            assertThat(store.load().revision()).isEqualTo("2");
            assertThat(certificate.certificateChain()).containsExactly(leaf, intermediate.certificate());
            assertThat(certificate.issuerTrustAnchor()).contains(root.certificate());
        }

        try (OrionKeyMaterial service = OrionKeyMaterial.open(new KeyMaterialRepository(
                store,
                new KeyMaterialCodec(options()),
                new OrionEventManager()))) {
            CertificateIdentity certificate = service.certificateIdentity(new TlsMaterialConfiguration(
                    service.read(new KeyMaterialId(identity.alias(), 2)).id(),
                    Optional.of(rootDescriptor), List.of(), TlsClientAuthentication.DISABLED));
            assertThat(certificate.certificateChain()).hasSize(2);
            assertThat(certificate.issuerTrustAnchor()).contains(root.certificate());
            assertThat(certificate.createContext().getProtocol()).isEqualTo("TLS");
        }
    }

    @Test
    void rejectsInvalidCertificateChainsBeforeSaving() throws Exception {
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        KeyMaterialId identity = new KeyMaterialId("https-identity-v1", 1);
        KeyMaterialId rootDescriptor = new KeyMaterialId("public-root-v1", 1);
        TestCertificateChain.Authority root = TestCertificateChain.root("Public Root");
        TestCertificateChain.Authority otherRoot = TestCertificateChain.root("Other Root");
        TestCertificateChain.Authority intermediate = TestCertificateChain.intermediate("Intermediate", root);
        try (OrionKeyMaterial service = OrionKeyMaterial.open(new KeyMaterialRepository(
                store,
                new KeyMaterialCodec(options()),
                new OrionEventManager()))) {

            CertificateIdentity certificate = service.certificateIdentity(new TlsMaterialConfiguration(identity,
                    Optional.of(rootDescriptor), List.of(), TlsClientAuthentication.DISABLED));
            generateKey(service, identity, Set.of((KeyMaterialPurpose.TLS_IDENTITY)), (KeyMaterialAlgorithm.RSA), 2048);
            KeyPair domainKey = service.read(identity).keyPair();
            KeyPair otherKey = KeyPairGenerator.getInstance("RSA").generateKeyPair();
            X509Certificate validLeaf = TestCertificateChain.leaf("orion.example", domainKey, intermediate);
            X509Certificate wrongLeaf = TestCertificateChain.leaf("other.example", otherKey, intermediate);
            TestCertificateChain.Authority unrelatedIntermediate =
                    TestCertificateChain.intermediate("Unrelated", otherRoot);

            assertThatThrownBy(() -> certificate.installCertificateChain(
                    List.of(wrongLeaf, intermediate.certificate()), Optional.of(root.certificate())))
                    .hasMessageContaining("public key");
            assertThatThrownBy(() -> certificate.installCertificateChain(
                    List.of(validLeaf, unrelatedIntermediate.certificate()),
                    Optional.of(otherRoot.certificate())))
                    .hasMessageContaining("signature");
            assertThatThrownBy(() -> certificate.installCertificateChain(
                    List.of(validLeaf, intermediate.certificate()), Optional.of(otherRoot.certificate())))
                    .hasMessageContaining("issuer root");
            assertThatThrownBy(() -> certificate.installCertificateChain(
                    List.of(validLeaf, intermediate.certificate(), root.certificate()),
                    Optional.of(root.certificate())))
                    .hasMessageContaining("must not include")
                    .hasMessageContaining("root");
            assertThatThrownBy(() -> certificate.installCertificateChain(
                    List.of(nonX509Certificate(), intermediate.certificate()),
                    Optional.of(root.certificate())))
                    .hasMessageContaining("X.509");
            assertThat(store.load().revision()).isEqualTo("1");
        }
    }

    @Test
    void keepsServerIssuerAndClientTrustRolesSeparate() throws Exception {
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        KeyMaterialId identity = new KeyMaterialId("https-v1", 1);
        KeyMaterialId serverRoot = new KeyMaterialId("server-root-v1", 1);
        KeyMaterialId clientRoot = new KeyMaterialId("client-root-v1", 1);
        TestCertificateChain.Authority serverAuthority = TestCertificateChain.root("Server Root");
        TestCertificateChain.Authority clientAuthority = TestCertificateChain.root("Client Root");
        KeyPair trustedClientKey = TestCertificateChain.keyPair();
        X509Certificate trustedClientCertificate = TestCertificateChain.leaf(
                "trusted-client", trustedClientKey, clientAuthority);
        KeyPair issuerSignedClientKey = TestCertificateChain.keyPair();
        X509Certificate issuerSignedClientCertificate = TestCertificateChain.leaf(
                "issuer-signed-client", issuerSignedClientKey, serverAuthority);
        try (OrionKeyMaterial service = OrionKeyMaterial.open(new KeyMaterialRepository(
                store,
                new KeyMaterialCodec(options()),
                new OrionEventManager()))) {
            KeyPair domainKey = generateKey(service, identity, Set.of((KeyMaterialPurpose.TLS_IDENTITY)), (KeyMaterialAlgorithm.RSA), 2048);
            KeyMaterialId installedIdentity = installCertificateChain(service, identity,
                    List.of(TestCertificateChain.leaf("orion.example", domainKey, serverAuthority)),
                    Optional.empty(), Optional.empty());
            setTrustedCertificate(service, serverRoot, Set.of(KeyMaterialPurpose.TRUST_ANCHOR), KeyMaterialAlgorithm.RSA, serverAuthority.certificate());
            setTrustedCertificate(service, clientRoot, Set.of(KeyMaterialPurpose.TRUST_ANCHOR), KeyMaterialAlgorithm.RSA, clientAuthority.certificate());

            CertificateIdentity disabled = service.certificateIdentity(new TlsMaterialConfiguration(
                    installedIdentity,
                    Optional.of(serverRoot),
                    List.of(),
                    TlsClientAuthentication.DISABLED));
            CertificateIdentity wanted = service.certificateIdentity(new TlsMaterialConfiguration(
                    installedIdentity,
                    Optional.of(serverRoot),
                    List.of(clientRoot),
                    TlsClientAuthentication.WANT));
            CertificateIdentity required = service.certificateIdentity(new TlsMaterialConfiguration(
                    installedIdentity,
                    Optional.of(serverRoot),
                    List.of(clientRoot),
                    TlsClientAuthentication.REQUIRED));
            SSLContext trustedClient = clientContext(
                    serverAuthority.certificate(), trustedClientKey, trustedClientCertificate);
            SSLContext issuerSignedClient = clientContext(
                    serverAuthority.certificate(), issuerSignedClientKey, issuerSignedClientCertificate);

            assertThat(required.clientAuthentication()).isEqualTo(TlsClientAuthentication.REQUIRED);
            assertThat(required.issuerTrustAnchor()).contains(serverAuthority.certificate());
            assertThat(handshake(disabled, issuerSignedClient)).isEqualTo(new HandshakeResult(true, false));
            assertThat(handshake(wanted, trustedClient)).isEqualTo(new HandshakeResult(true, true));
            assertThat(handshake(required, trustedClient)).isEqualTo(new HandshakeResult(true, true));
            assertThat(handshake(required, issuerSignedClient).successful()).isFalse();

            CertificateIdentity sharedRootRoles = service.certificateIdentity(new TlsMaterialConfiguration(
                    installedIdentity,
                    Optional.of(serverRoot),
                    List.of(clientRoot, serverRoot),
                    TlsClientAuthentication.REQUIRED));
            assertThat(handshake(sharedRootRoles, issuerSignedClient))
                    .isEqualTo(new HandshakeResult(true, true));
            assertThatThrownBy(() -> service.certificateIdentity(new TlsMaterialConfiguration(
                    installedIdentity,
                    Optional.of(serverRoot),
                    List.of(),
                    TlsClientAuthentication.REQUIRED)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("client trust");
        }
    }

    @Test
    void sealsConfigurationEnvelopeAcrossMaterialReload() throws Exception {
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        KeyMaterialId cipher = new KeyMaterialId("configuration-v1", 1);
        ConfigurationSecretContext context = new ConfigurationSecretContext("github-token", "access-token");
        byte[] plaintext = "database-password".getBytes(StandardCharsets.UTF_8);
        ConfigurationSecretEnvelopeCodec codec = new ConfigurationSecretEnvelopeCodec();
        String serializedEnvelope;
        try (OrionKeyMaterial service = OrionKeyMaterial.open(new KeyMaterialRepository(
                store,
                new KeyMaterialCodec(options()),
                new OrionEventManager()))) {
            ConfigurationSecretEnvelope envelope = service.seal(cipher, plaintext, context);
            serializedEnvelope = codec.serialize(envelope);
        }

        try (OrionKeyMaterial reloaded = OrionKeyMaterial.open(new KeyMaterialRepository(
                store,
                new KeyMaterialCodec(options()),
                new OrionEventManager()))) {
            ConfigurationSecretEnvelope envelope = codec.parse(serializedEnvelope);

            assertThat(envelope.keyAlias()).isEqualTo(cipher.alias());
            assertThat(envelope.keyVersion()).isEqualTo(cipher.version());
            assertThat(envelope.wrappingAlgorithm()).isEqualTo("AESWrap");
            assertThat(envelope.encryptionAlgorithm()).isEqualTo("AES/GCM/NoPadding");
            assertThat(envelope.encoding()).isEqualTo("base64url");
            assertThat(envelope.ciphertext()).isNotEqualTo(plaintext);
            assertThat(reloaded.open(cipher, envelope, context))
                    .isEqualTo(plaintext);
        }
    }

    @Test
    void usesFreshDataKeyNonceAndCiphertextForEverySeal() throws Exception {
        KeyMaterialId cipher = new KeyMaterialId("configuration-v1", 1);
        try (OrionKeyMaterial service = OrionKeyMaterial.open(new KeyMaterialRepository(
                new InMemoryBlobStorage(),
                new KeyMaterialCodec(options()),
                new OrionEventManager()))) {
            byte[] plaintext = "same-password".getBytes(StandardCharsets.UTF_8);

            ConfigurationSecretEnvelope first = service.seal(cipher, plaintext, context());
            ConfigurationSecretEnvelope second = service.seal(cipher, plaintext, context());

            assertThat(first.wrappedDataKey()).isNotEqualTo(second.wrappedDataKey());
            assertThat(first.nonce()).isNotEqualTo(second.nonce());
            assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
            assertThat(service.open(cipher, first, context())).isEqualTo(plaintext);
            assertThat(service.open(cipher, second, context())).isEqualTo(plaintext);
        }
    }

    @Test
    void rejectsEveryChangedContextWithTypedAuthenticationFailure() throws Exception {
        KeyMaterialId cipher = new KeyMaterialId("configuration-v1", 1);
        try (OrionKeyMaterial service = OrionKeyMaterial.open(new KeyMaterialRepository(
                new InMemoryBlobStorage(),
                new KeyMaterialCodec(options()),
                new OrionEventManager()))) {
            ConfigurationSecretEnvelope envelope = service.seal(cipher,
                    "password".getBytes(StandardCharsets.UTF_8), context());
            List<ConfigurationSecretContext> changed = List.of(
                    new ConfigurationSecretContext("other", "access-token"),
                    new ConfigurationSecretContext("github-token", "other"));

            for (ConfigurationSecretContext candidate : changed) {
                assertSecretFailure(
                        () -> service.open(cipher, envelope, candidate),
                        ConfigurationSecretException.Reason.AUTHENTICATION_FAILED);
            }
        }
    }

    @Test
    void rejectsTamperedEnvelopeAndMismatchedMaterialWithoutSecretValues() throws Exception {
        KeyMaterialId cipher = new KeyMaterialId("configuration-v1", 1);
        try (OrionKeyMaterial service = OrionKeyMaterial.open(new KeyMaterialRepository(
                new InMemoryBlobStorage(),
                new KeyMaterialCodec(options()),
                new OrionEventManager()))) {
            ConfigurationSecretEnvelope envelope = service.seal(cipher,
                    "do-not-report".getBytes(StandardCharsets.UTF_8), context());

            assertSecretFailure(
                    () -> service.open(cipher,
                            withWrappedKey(envelope, changed(envelope.wrappedDataKey())),
                            context()),
                    ConfigurationSecretException.Reason.AUTHENTICATION_FAILED);
            assertSecretFailure(
                    () -> service.open(cipher, withNonce(envelope, changed(envelope.nonce())), context()),
                    ConfigurationSecretException.Reason.AUTHENTICATION_FAILED);
            assertSecretFailure(
                    () -> service.open(cipher, withCiphertext(envelope, changed(envelope.ciphertext())), context()),
                    ConfigurationSecretException.Reason.AUTHENTICATION_FAILED);
            assertSecretFailure(
                    () -> service.open(cipher,
                            withAlias(envelope, "configuration-v2"),
                            context()),
                    ConfigurationSecretException.Reason.MATERIAL_MISMATCH);
            assertSecretFailure(
                    () -> service.open(cipher, withVersion(envelope, 2), context()),
                    ConfigurationSecretException.Reason.MATERIAL_MISMATCH);
        }
    }

    @Test
    void rejectsSigningWithSshOnlyMaterial() throws Exception {
        try (OrionKeyMaterial service = service()) {
            KeyMaterialId ssh = new KeyMaterialId("ssh-host-v1", 1);
            generateKey(service, ssh, Set.of((KeyMaterialPurpose.SSH_HOST)), (KeyMaterialAlgorithm.RSA), 2048);

            assertThatThrownBy(() -> service.sign(ssh, new byte[]{1}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("signing");
        }
    }

    private static KeyPair generateKey(OrionKeyMaterial material, KeyMaterialId descriptor, Set<KeyMaterialPurpose> purposes,
            KeyMaterialAlgorithm algorithm, int keySize) throws Exception {
        material.withModification(material.revision(), modification -> {
            modification.generateKey(descriptor.alias(), purposes, algorithm, keySize);
            modification.apply();
        });
        return material.read(descriptor).keyPair();
    }

    private static void setTrustedCertificate(OrionKeyMaterial material, KeyMaterialId descriptor, Set<KeyMaterialPurpose> purposes,
            KeyMaterialAlgorithm algorithm, X509Certificate certificate) throws Exception {
        material.withModification(material.revision(), modification -> {
            modification.setTrustedCertificate(descriptor.alias(), purposes, algorithm, certificate);
            modification.apply();
        });
    }

    private static KeyMaterialId installCertificateChain(OrionKeyMaterial material,
            KeyMaterialId identity, List<X509Certificate> chain, Optional<KeyMaterialId> issuer,
            Optional<X509Certificate> root) throws Exception {
        KeyMaterialModification modification = material.modify(material.revision());
        try {
            if (root.isPresent()) {
                KeyMaterialId rootId = issuer.orElseThrow();
                if (material.contains(rootId.alias(), Set.of())) {
                    modification.setTrustedCertificate(rootId, root.orElseThrow());
                } else {
                    modification.setTrustedCertificate(rootId.alias(), Set.of(KeyMaterialPurpose.TRUST_ANCHOR),
                            KeyMaterialAlgorithm.RSA, root.orElseThrow());
                }
            }
            KeyMaterialId id = modification.installCertificateChain(identity, chain, issuer);
            modification.apply();
            return material.read(id).id();
        } finally {
            modification.discard();
        }
    }

    private static void generateKeys(OrionKeyMaterial service, KeyMaterialId first,
            KeyMaterialId second) throws Exception {
        KeyMaterialModification modification = service.modify(service.revision());
        try {
            modification.generateKey(first.alias(), Set.of(KeyMaterialPurpose.ACME_ACCOUNT), KeyMaterialAlgorithm.RSA, 2048);
            modification.generateKey(second.alias(), Set.of(KeyMaterialPurpose.TLS_IDENTITY), KeyMaterialAlgorithm.RSA, 2048);
            modification.apply();
        } finally {
            modification.discard();
        }
    }

    private static OrionKeyMaterial service() throws Exception {
        return OrionKeyMaterial.open(new KeyMaterialRepository(
                new InMemoryBlobStorage(),
                new KeyMaterialCodec(options()),
                new OrionEventManager()));
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
            String alias) {
        return copy(envelope, alias, envelope.keyVersion(), envelope.wrappedDataKey(),
                envelope.nonce(), envelope.ciphertext());
    }

    private static ConfigurationSecretEnvelope withVersion(
            ConfigurationSecretEnvelope envelope,
            long version) {
        return copy(envelope, envelope.keyAlias(), version, envelope.wrappedDataKey(),
                envelope.nonce(), envelope.ciphertext());
    }

    private static ConfigurationSecretEnvelope copy(
            ConfigurationSecretEnvelope envelope,
            String alias,
            long version,
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
            CertificateIdentity certificate,
            SSLContext clientContext) throws Exception {
        try (SSLServerSocket serverSocket = (SSLServerSocket) certificate
                .createContext()
                .getServerSocketFactory()
                .createServerSocket(0);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            configureClientAuthentication(serverSocket, certificate.clientAuthentication());
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
