package pro.deta.orion.transport.http;

import pro.deta.orion.event.OrionEventManager;

import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.keymaterial.InMemoryKeyMaterialContentStore;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.KeyMaterialInventoryEntry;
import pro.deta.orion.keymaterial.KeyMaterialOptions;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.keymaterial.OrionKeyMaterial;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.transport.http.OrionHttpRouteDefinition.Authorization.APPLICATION_ADMIN;

class OrionAdminKeyMaterialRouteTest {
    private static final KeyMaterialDescriptor ACCOUNT = new KeyMaterialDescriptor(
            "account", Set.of(KeyMaterialPurpose.ACME_ACCOUNT),
            KeyMaterialAlgorithm.RSA, 1);

    @Test
    void exposesOnlyPublicInventoryToApplicationAdmins() throws Exception {
        KeyMaterialInventoryEntry entry = new KeyMaterialInventoryEntry(
                new KeyMaterialDescriptor("acme-identity",
                        Set.of(KeyMaterialPurpose.TLS_IDENTITY, KeyMaterialPurpose.SERVER_SIGNING),
                        KeyMaterialAlgorithm.RSA, 1),
                "-----BEGIN PUBLIC KEY-----\npublic\n-----END PUBLIC KEY-----", "fingerprint", List.of());
        OrionKeyMaterial material = new OrionKeyMaterial() {
            @Override
            public List<KeyMaterialInventoryEntry> inventory(Set<KeyMaterialPurpose> requiredPurposes) {
                return List.of(entry);
            }
        };
        OrionAdminKeyMaterialRoute route = new OrionAdminKeyMaterialRoute(material, new ObjectMapper());

        assertThat(route.definition().authorization()).isEqualTo(APPLICATION_ADMIN);
        assertThat(route.doGet(null).body()).isEqualTo(Map.of("entries", List.of(entry)));
        com.fasterxml.jackson.databind.JsonNode descriptor = new ObjectMapper()
                .valueToTree(route.doGet(null).body()).path("entries").get(0).path("descriptor");
        assertThat(descriptor.path("alias").asText()).isEqualTo("acme-identity");
        assertThat(descriptor.path("algorithm").asText()).isEqualTo("RSA");
        assertThat(descriptor.path("version").asLong()).isEqualTo(1);
        assertThat(descriptor.path("purposes").toString()).contains("TLS_IDENTITY", "SERVER_SIGNING");
    }

    @Test
    void publishesTheCreatedKeyOnlyAfterTheStoreAcceptsIt() throws Exception {
        class ObservingStore extends InMemoryKeyMaterialContentStore {
            private OrionKeyMaterial material;

            @Override
            public synchronized String write(byte[] bytes, String expectedVersion) throws IOException {
                try {
                    assertThat(material.contains(ACCOUNT)).isFalse();
                } catch (GeneralSecurityException failure) {
                    throw new IOException(failure);
                }
                return super.write(bytes, expectedVersion);
            }
        }
        ObservingStore store = new ObservingStore();
        try (OrionKeyMaterial material = open(store)) {
            store.material = material;
            OrionHttpResponse response = post(material, """
                    {"alias":"account","purpose":"ACME_ACCOUNT"}
                    """);

            assertThat(response.status()).isEqualTo(201);
            assertThat(response.body()).isNull();
            assertThat(material.contains(ACCOUNT)).isTrue();
            assertThat(material.version()).isEqualTo(1);
        }
        try (OrionKeyMaterial reloaded = open(store)) {
            assertThat(reloaded.contains(ACCOUNT)).isTrue();
        }
    }

    @Test
    void importsTheRequestedRsaKeyWithoutReturningSecrets() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair original = generator.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(original.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        String body = new ObjectMapper().writeValueAsString(Map.of(
                "alias", "account", "purpose", "ACME_ACCOUNT", "privateKeyPem", pem));
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        try (OrionKeyMaterial material = open(store)) {
            OrionHttpResponse response = post(material, body);
            assertThat(response.status()).isEqualTo(201);
            assertThat(response.body()).isNull();
        }
        try (OrionKeyMaterial reloaded = open(store)) {
            assertThat(reloaded.keyPair(ACCOUNT).getPrivate().getEncoded())
                    .isEqualTo(original.getPrivate().getEncoded());
            assertThat(reloaded.publicKey(ACCOUNT).getEncoded()).isEqualTo(original.getPublic().getEncoded());
        }
    }

    @Test
    void rejectsAnExistingAliasRegardlessOfItsPurposeWithoutReplacingTheKey() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        try (OrionKeyMaterial material = open(store)) {
            assertThat(post(material, """
                    {"alias":"account","purpose":"ACME_ACCOUNT"}
                    """).status()).isEqualTo(201);
            byte[] publicKey = material.publicKey(ACCOUNT).getEncoded();
            String beforeRevision = store.revision().orElseThrow();
            byte[] beforeBytes = store.read(beforeRevision);

            for (String purpose : List.of("ACME_ACCOUNT", "TLS_IDENTITY")) {
                String body = new ObjectMapper().writeValueAsString(
                        Map.of("alias", "account", "purpose", purpose));
                assertThat(post(material, body).status()).isEqualTo(400);
            }
            assertThat(material.publicKey(ACCOUNT).getEncoded()).isEqualTo(publicKey);
            assertThat(material.version()).isEqualTo(1);
            assertThat(store.read(store.revision().orElseThrow())).isEqualTo(beforeBytes);
            assertThat(store.revision().orElseThrow()).isEqualTo(beforeRevision);
        }
    }

    @Test
    void rejectsInvalidNamesPurposesAndOversizedRequestsWithoutSaving() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        try (OrionKeyMaterial material = open(store)) {
            for (String body : List.of("null", "{", "{\"purpose\":\"UNKNOWN\"}",
                    "{\"purpose\":\"ACME_ACCOUNT\"}",
                    "{\"alias\":\"account\"}",
                    "{\"alias\":\"INVALID\",\"purpose\":\"ACME_ACCOUNT\"}",
                    "{\"alias\":\"../account\",\"purpose\":\"ACME_ACCOUNT\"}",
                    "{\"alias\":\"account\",\"purpose\":\"SERVER_SIGNING\"}")) {
                assertThat(post(material, body).status()).isEqualTo(400);
            }
            assertThat(post(material, "x".repeat(32769)).status()).isEqualTo(413);
            assertThat(material.inventory(Set.of())).isEmpty();
            assertThat(store.revision()).isEmpty();
        }
    }

    @Test
    void sanitizesMalformedImportFailuresWithoutSaving() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        try (OrionKeyMaterial material = open(store)) {
            OrionHttpResponse response = post(material, """
                    {"alias":"invalid","purpose":"ACME_ACCOUNT","privateKeyPem":"private-secret"}
                    """);
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body().toString()).doesNotContain("private-secret");
            assertThat(material.inventory(Set.of())).isEmpty();
            assertThat(store.revision()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reportsFailedPublicationAndReopensOnlyTheDurableKey(boolean committed) throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore() {
            @Override
            public synchronized String write(byte[] bytes, String expectedVersion) throws IOException {
                if (committed) super.write(bytes, expectedVersion);
                throw new IOException("private-storage-detail");
            }
        };
        try (OrionKeyMaterial material = open(store)) {
            OrionHttpResponse response = post(material, """
                    {"alias":"account","purpose":"ACME_ACCOUNT"}
                    """);
            assertThat(response.status()).isEqualTo(503);
            assertThat(response.body().toString()).doesNotContain("private-storage-detail");
            assertThatThrownBy(() -> material.contains(ACCOUNT)).isInstanceOf(IllegalStateException.class);
        }
        try (OrionKeyMaterial reloaded = open(store)) {
            assertThat(reloaded.contains(ACCOUNT)).isEqualTo(committed);
            assertThat(reloaded.version()).isEqualTo(committed ? 1 : 0);
        }
    }

    @Test
    void reportsUnavailableMaterialAsAServiceFailure() {
        assertThat(post(OrionKeyMaterial.unavailable(), """
                {"alias":"account","purpose":"ACME_ACCOUNT"}
                """).status()).isEqualTo(503);
    }

    private static OrionHttpResponse post(OrionKeyMaterial material, String body) {
        OrionAdminKeyMaterialRoute route = new OrionAdminKeyMaterialRoute(material, new ObjectMapper());
        return route.doPost(AcmeAdministrationTest.httpRequest("POST", "/api/admin/key-material",
                body, AcmeAdministrationTest.admin()));
    }

    private static OrionKeyMaterial open(InMemoryKeyMaterialContentStore store) throws Exception {
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12("test-password".toCharArray())) {
            return OrionKeyMaterial.open(store, options,
                    new OrionEventManager());
        }
    }
}
