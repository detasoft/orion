package pro.deta.orion.keymaterial;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServerIdentityMaterialTest {
    private static final KeyMaterialScope CLUSTER = KeyMaterialScope.cluster("orion-prod");

    @Test
    void createsActiveIdentityOnlyForANewStoreAndReloadsIt() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        SigningMaterialSet signing = new SigningMaterialSet(rsa("server-signing-v1", 1), List.of());
        byte[] payload = "jwt-input".getBytes(StandardCharsets.UTF_8);
        byte[] signature;

        try (ServerIdentityMaterial material = ServerIdentityMaterial.open(
                store, options(), signing, 2048)) {
            signature = material.sign(payload);
            assertThat(material.activeKeyId()).isEqualTo("server-signing-v1");
            assertThat(material.verify("server-signing-v1", payload, signature)).isTrue();
            assertThat(material.publicKeys()).hasSize(1);
        }

        try (ServerIdentityMaterial reloaded = ServerIdentityMaterial.open(
                store, options(), signing, 2048)) {
            assertThat(reloaded.verify("server-signing-v1", payload, signature)).isTrue();
            assertThat(reloaded.verify("unknown", payload, signature)).isFalse();
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
        try (ServerIdentityMaterial material = ServerIdentityMaterial.open(
                store, options(), signing, 2048)) {
            byte[] activeSignature = material.sign(payload);

            assertThat(material.activeKeyId()).isEqualTo("server-signing-v2");
            assertThat(material.verify("server-signing-v1", payload, oldSignature)).isTrue();
            assertThat(material.verify("server-signing-v2", payload, activeSignature)).isTrue();
            assertThat(material.verify("server-signing-v1", payload, activeSignature)).isFalse();
            assertThat(material.publicKeys()).hasSize(1);
            assertThat(material.retainedPublicKeys()).hasSize(1);
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

        assertThatThrownBy(() -> ServerIdentityMaterial.open(store, options(), missing, 2048))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("server-signing-v1");
        assertThat(store.read().orElseThrow().version()).isEqualTo(before);
    }

    @Test
    void rejectsNonRsaServerIdentityBeforeSigning() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialDescriptor ed25519 = descriptor(
                "server-signing-ed25519",
                KeyMaterialPurpose.SERVER_SIGNING,
                KeyMaterialAlgorithm.ED25519,
                1);

        assertThatThrownBy(() -> ServerIdentityMaterial.open(
                store,
                options(),
                new SigningMaterialSet(ed25519, List.of()),
                0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RSA");
    }

    private static KeyMaterialDescriptor rsa(String alias, long version) {
        return descriptor(alias, KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialAlgorithm.RSA, version);
    }

    private static KeyMaterialDescriptor descriptor(
            String alias,
            KeyMaterialPurpose purpose,
            KeyMaterialAlgorithm algorithm,
            long version) {
        return new KeyMaterialDescriptor(
                new KeyMaterialAlias(alias),
                purpose,
                algorithm,
                new KeyMaterialVersion(version),
                CLUSTER);
    }

    private static KeyMaterialOptions options() {
        return KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password());
    }
}
