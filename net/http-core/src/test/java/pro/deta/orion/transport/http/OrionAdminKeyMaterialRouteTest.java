package pro.deta.orion.transport.http;

import java.security.cert.X509Certificate;
import pro.deta.orion.test.certificates.TestCertificateChain;
import pro.deta.orion.keymaterial.KeyMaterialCodec;

import pro.deta.orion.keymaterial.KeyMaterialContentStore;

import pro.deta.orion.event.OrionEventManager;

import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.keymaterial.InMemoryKeyMaterialContentSource;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
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
        try (OrionKeyMaterial material = open(new InMemoryKeyMaterialContentSource())) {
            material.withModification(material.revision(), modification -> {
                modification.generateKey(new KeyMaterialDescriptor("acme-identity",
                        Set.of(KeyMaterialPurpose.TLS_IDENTITY, KeyMaterialPurpose.SERVER_SIGNING),
                        KeyMaterialAlgorithm.RSA, 1), 2048);
                modification.generateKey(material.configurationCipherKey(), 256);
                modification.apply();
            });
            OrionAdminKeyMaterialRoute route = new OrionAdminKeyMaterialRoute(material, new ObjectMapper());
            OrionHttpResponse response = route.doGet(null);
            assertThat(route.definition().authorization()).isEqualTo(APPLICATION_ADMIN);
            com.fasterxml.jackson.databind.JsonNode body = new ObjectMapper().valueToTree(response.body());
            com.fasterxml.jackson.databind.JsonNode descriptor = body.path("entries").get(0).path("descriptor");
            assertThat(descriptor.path("alias").asText()).isEqualTo("acme-identity");
            assertThat(descriptor.path("algorithm").asText()).isEqualTo("RSA");
            assertThat(descriptor.path("version").asLong()).isEqualTo(1);
            assertThat(descriptor.path("purposes").toString()).contains("TLS_IDENTITY", "SERVER_SIGNING");
            assertThat(body.path("entries").get(0).path("publicKeyPem").asText()).contains("BEGIN PUBLIC KEY");
            assertThat(body.path("entries").get(1).path("publicKeyPem").isNull()).isTrue();
            assertThat(body.toString()).doesNotContain("PRIVATE KEY", "privateKey", "secretKey", "content");
        }
    }

    @Test
    void formatsTheSelectedCertificateVersionAsPublicMetadata() throws Exception {
        try (OrionKeyMaterial material = open(new InMemoryKeyMaterialContentSource())) {
            KeyMaterialDescriptor identity = new KeyMaterialDescriptor("identity",
                    Set.of(KeyMaterialPurpose.TLS_IDENTITY), KeyMaterialAlgorithm.RSA, 1);
            material.withModification(material.revision(), modification -> {
                modification.generateKey(identity, 2048);
                modification.apply();
            });
            TestCertificateChain.Authority root =
                    TestCertificateChain.root("Public root");
            X509Certificate leaf = TestCertificateChain.leaf(
                    "orion.test", material.read(identity.id()).keyPair(), root);
            material.withModification(material.revision(), modification -> {
                modification.installCertificateChain(identity, List.of(leaf), java.util.Optional.empty());
                modification.apply();
            });
            KeyMaterialInventoryEntry entry = KeyMaterialInventoryFormatter.format(material).getFirst();
            assertThat(entry.descriptor().version()).isEqualTo(2);
            assertThat(entry.publicKeySha256Fingerprint()).matches("([0-9a-f]{2}:){31}[0-9a-f]{2}");
            assertThat(entry.certificates()).hasSize(1);
            KeyMaterialInventoryEntry.CertificateDetails certificate = entry.certificates().getFirst();
            assertThat(certificate.subject()).isEqualTo(leaf.getSubjectX500Principal().getName());
            assertThat(certificate.issuer()).isEqualTo(leaf.getIssuerX500Principal().getName());
            assertThat(certificate.serialNumber()).isEqualTo(leaf.getSerialNumber().toString(16));
            assertThat(certificate.validUntil()).isEqualTo(leaf.getNotAfter().toInstant().toString());
            assertThat(certificate.validFrom()).isEqualTo(leaf.getNotBefore().toInstant().toString());
        }
    }

    @Test
    void publishesTheCreatedKeyOnlyAfterTheStoreAcceptsIt() throws Exception {
        class ObservingStore extends InMemoryKeyMaterialContentSource {
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
            assertThat(material.revision()).isEqualTo("1");
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
        InMemoryKeyMaterialContentSource store = new InMemoryKeyMaterialContentSource();
        try (OrionKeyMaterial material = open(store)) {
            OrionHttpResponse response = post(material, body);
            assertThat(response.status()).isEqualTo(201);
            assertThat(response.body()).isNull();
        }
        try (OrionKeyMaterial reloaded = open(store)) {
            assertThat(reloaded.read(ACCOUNT.id()).keyPair().getPrivate().getEncoded())
                    .isEqualTo(original.getPrivate().getEncoded());
            assertThat(reloaded.read(ACCOUNT.id()).publicKey().getEncoded())
                    .isEqualTo(original.getPublic().getEncoded());
        }
    }

    @Test
    void rejectsAnExistingAliasRegardlessOfItsPurposeWithoutReplacingTheKey() throws Exception {
        InMemoryKeyMaterialContentSource store = new InMemoryKeyMaterialContentSource();
        try (OrionKeyMaterial material = open(store)) {
            assertThat(post(material, """
                    {"alias":"account","purpose":"ACME_ACCOUNT"}
                    """).status()).isEqualTo(201);
            byte[] publicKey = material.read(ACCOUNT.id()).publicKey().getEncoded();
            String beforeRevision = store.load().revision();
            byte[] beforeBytes = store.read(beforeRevision);

            for (String purpose : List.of("ACME_ACCOUNT", "TLS_IDENTITY")) {
                String body = new ObjectMapper().writeValueAsString(
                        Map.of("alias", "account", "purpose", purpose));
                assertThat(post(material, body).status()).isEqualTo(400);
            }
            assertThat(material.read(ACCOUNT.id()).publicKey().getEncoded()).isEqualTo(publicKey);
            assertThat(material.revision()).isEqualTo("1");
            assertThat(store.load().bytes()).isEqualTo(beforeBytes);
            assertThat(store.load().revision()).isEqualTo(beforeRevision);
        }
    }

    @Test
    void rejectsInvalidNamesPurposesAndOversizedRequestsWithoutSaving() throws Exception {
        InMemoryKeyMaterialContentSource store = new InMemoryKeyMaterialContentSource();
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
            assertThat(material.inventory()).isEmpty();
            assertThat(store.load().bytes()).isNull();
        }
    }

    @Test
    void sanitizesMalformedImportFailuresWithoutSaving() throws Exception {
        InMemoryKeyMaterialContentSource store = new InMemoryKeyMaterialContentSource();
        try (OrionKeyMaterial material = open(store)) {
            OrionHttpResponse response = post(material, """
                    {"alias":"invalid","purpose":"ACME_ACCOUNT","privateKeyPem":"private-secret"}
                    """);
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body().toString()).doesNotContain("private-secret");
            assertThat(material.inventory()).isEmpty();
            assertThat(store.load().bytes()).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reportsFailedPublicationAndReopensOnlyTheDurableKey(boolean committed) throws Exception {
        InMemoryKeyMaterialContentSource store = new InMemoryKeyMaterialContentSource() {
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
            assertThat(reloaded.revision()).isEqualTo(committed ? "1" : null);
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

    private static OrionKeyMaterial open(InMemoryKeyMaterialContentSource store) throws Exception {
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12("test-password".toCharArray())) {
            return OrionKeyMaterial.open(new KeyMaterialContentStore(
                    store,
                    new KeyMaterialCodec(options),
                    new OrionEventManager()));
        }
    }
}
