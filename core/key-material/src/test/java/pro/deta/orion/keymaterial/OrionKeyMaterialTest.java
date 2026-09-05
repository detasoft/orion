package pro.deta.orion.keymaterial;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrionKeyMaterialTest {
    private static final KeyMaterialScope CLUSTER = KeyMaterialScope.cluster("orion-prod");

    @Test
    void createsActiveIdentityOnlyForANewStoreAndReloadsIt() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        SigningMaterialSet signing = new SigningMaterialSet(rsa("server-signing-v1", 1), List.of());
        byte[] payload = "jwt-input".getBytes(StandardCharsets.UTF_8);
        byte[] signature;

        try (OrionKeyMaterial material = OrionKeyMaterial.open(store, options(), signing, 2048)) {
            ServerIdentityCapability identity = material.serverIdentity();
            signature = identity.sign(payload);
            assertThat(identity.activeKeyId()).isEqualTo("server-signing-v1");
            assertThat(identity.verify("server-signing-v1", payload, signature)).isTrue();
            assertThat(identity.publicKeys()).hasSize(1);
        }

        try (OrionKeyMaterial material = OrionKeyMaterial.open(store, options(), signing, 2048)) {
            ServerIdentityCapability identity = material.serverIdentity();
            assertThat(identity.verify("server-signing-v1", payload, signature)).isTrue();
            assertThat(identity.verify("unknown", payload, signature)).isFalse();
        }
    }

    @Test
    void retainsExactOlderAliasForVerificationAcrossReload() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialDescriptor old = rsa("server-signing-v1", 1);
        KeyMaterialDescriptor active = rsa("server-signing-v2", 2);
        byte[] payload = "retained-jwt".getBytes(StandardCharsets.UTF_8);
        byte[] oldSignature;
        try (KeyMaterialService service = KeyMaterialService.open(store, options())) {
            service.generateKeyIfMissing(old, 2048);
            service.generateKeyIfMissing(active, 2048);
            oldSignature = KeyMaterialCapabilities.open(service, List.of(old, active))
                    .signing(old)
                    .sign(payload);
            service.save();
        }

        SigningMaterialSet signing = new SigningMaterialSet(active, List.of(old));
        try (OrionKeyMaterial material = OrionKeyMaterial.open(store, options(), signing, 2048)) {
            ServerIdentityCapability identity = material.serverIdentity();
            byte[] activeSignature = identity.sign(payload);

            assertThat(identity.activeKeyId()).isEqualTo("server-signing-v2");
            assertThat(identity.verify("server-signing-v1", payload, oldSignature)).isTrue();
            assertThat(identity.verify("server-signing-v2", payload, activeSignature)).isTrue();
            assertThat(identity.verify("server-signing-v1", payload, activeSignature)).isFalse();
            assertThat(identity.publicKeys()).hasSize(1);
            assertThat(identity.retainedPublicKeys()).hasSize(1);
        }
    }

    @Test
    void doesNotGenerateMissingIdentityIntoAnExistingStore() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialDescriptor existing = rsa("other-signing", 1);
        try (KeyMaterialService service = KeyMaterialService.open(store, options())) {
            service.generateKeyIfMissing(existing, 2048);
            service.save();
        }
        String before = store.read().orElseThrow().version();
        SigningMaterialSet missing = new SigningMaterialSet(rsa("server-signing-v1", 1), List.of());

        assertThatThrownBy(() -> OrionKeyMaterial.open(store, options(), missing, 2048))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("server-signing-v1");
        assertThat(store.read().orElseThrow().version()).isEqualTo(before);
    }

    @Test
    void rejectsNonRsaServerIdentityBeforeSigning() {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialDescriptor ed25519 = descriptor(
                "server-signing-ed25519",
                KeyMaterialPurpose.SERVER_SIGNING,
                KeyMaterialAlgorithm.ED25519,
                1,
                CLUSTER);

        assertThatThrownBy(() -> OrionKeyMaterial.open(
                store,
                options(),
                new SigningMaterialSet(ed25519, List.of()),
                0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RSA");
    }

    @Test
    void derivesLateBoundAcmeAndTlsCapabilitiesFromOneOwner() throws Exception {
        CountingStore store = new CountingStore();
        OrionKeyMaterial material = OrionKeyMaterial.open(
                store,
                options(),
                new SigningMaterialSet(rsa("server-signing-v1", 1), List.of()),
                2048);
        KeyMaterialDescriptor account = descriptor(
                "acme-account-v1", KeyMaterialPurpose.ACME_ACCOUNT, KeyMaterialAlgorithm.RSA, 1, CLUSTER);
        KeyMaterialDescriptor identity = descriptor(
                "https-v1", KeyMaterialPurpose.TLS_IDENTITY, KeyMaterialAlgorithm.RSA, 1, CLUSTER);
        AcmeMaterialConfiguration acme = new AcmeMaterialConfiguration(account, identity, Optional.empty());
        TlsMaterialConfiguration tls = new TlsMaterialConfiguration(
                identity, Optional.empty(), List.of(), TlsClientAuthentication.DISABLED);

        AcmeKeyMaterial keys = material.acme().acquire(acme, 2048, 2048);
        assertThatThrownBy(() -> material.tls().createContext(tls))
                .hasMessageContaining("issued certificate chain");
        TestCertificateChain.Authority root = TestCertificateChain.root("Public Root");
        TestCertificateChain.Authority intermediate = TestCertificateChain.intermediate("Intermediate", root);
        material.acme().installCertificateChain(
                acme,
                List.of(TestCertificateChain.leaf("orion.example", keys.domainKeyPair(), intermediate)),
                Optional.empty());

        assertThat(material.serverIdentity().activeKeyId()).isEqualTo("server-signing-v1");
        assertThat(material.acme().certificateChain(acme).orElseThrow()).hasSize(1);
        assertThat(material.tls().createContext(tls).getProtocol()).isEqualTo("TLS");
        assertThat(store.readCalls).isEqualTo(1);
        assertThat(OrionKeyMaterial.class.getMethods())
                .noneMatch(method -> method.getReturnType().equals(KeyMaterialService.class));

        material.close();
        material.close();
        assertThatThrownBy(() -> material.acme().acquire(acme, 2048, 2048))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void rejectsLateBoundMaterialOutsideTheOwnerClusterScope() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        try (OrionKeyMaterial material = OrionKeyMaterial.open(
                store,
                options(),
                new SigningMaterialSet(rsa("server-signing-v1", 1), List.of()),
                2048)) {
            KeyMaterialDescriptor account = descriptor(
                    "acme-account-v1",
                    KeyMaterialPurpose.ACME_ACCOUNT,
                    KeyMaterialAlgorithm.RSA,
                    1,
                    KeyMaterialScope.cluster("other"));
            KeyMaterialDescriptor identity = descriptor(
                    "https-v1",
                    KeyMaterialPurpose.TLS_IDENTITY,
                    KeyMaterialAlgorithm.RSA,
                    1,
                    KeyMaterialScope.cluster("other"));
            AcmeMaterialConfiguration configuration = new AcmeMaterialConfiguration(
                    account, identity, Optional.empty());

            assertThatThrownBy(() -> material.acme().acquire(configuration, 2048, 2048))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("owner cluster");
        }
    }

    private static KeyMaterialDescriptor rsa(String alias, long version) {
        return descriptor(alias, KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialAlgorithm.RSA, version, CLUSTER);
    }

    private static KeyMaterialDescriptor descriptor(
            String alias,
            KeyMaterialPurpose purpose,
            KeyMaterialAlgorithm algorithm,
            long version,
            KeyMaterialScope scope) {
        return new KeyMaterialDescriptor(
                new KeyMaterialAlias(alias),
                purpose,
                algorithm,
                new KeyMaterialVersion(version),
                scope);
    }

    private static KeyMaterialOptions options() {
        return KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password());
    }

    private static final class CountingStore implements KeyMaterialContentStore {
        private final InMemoryKeyMaterialContentStore delegate = new InMemoryKeyMaterialContentStore();
        private int readCalls;

        @Override
        public Optional<KeyMaterialSnapshot> read() throws IOException {
            readCalls++;
            return delegate.read();
        }

        @Override
        public String write(byte[] bytes, String expectedVersion) throws IOException {
            return delegate.write(bytes, expectedVersion);
        }
    }
}
