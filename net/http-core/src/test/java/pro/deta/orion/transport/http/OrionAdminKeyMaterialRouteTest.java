package pro.deta.orion.transport.http;

import pro.deta.orion.util.VcChangeDescription;

import pro.deta.orion.util.VcRevision;

import pro.deta.orion.keymaterial.KeyMaterialOperation;

import java.security.cert.X509Certificate;
import pro.deta.orion.test.certificates.TestCertificateChain;
import pro.deta.orion.keymaterial.KeyMaterialCodec;

import pro.deta.orion.keymaterial.KeyMaterialRepository;

import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.storage.InMemoryBlobStorage;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialId;
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
    @org.junit.jupiter.api.extension.RegisterExtension
    final pro.deta.orion.test.util.OrionTestRuntime events = new pro.deta.orion.test.util.OrionTestRuntime();

    private static final KeyMaterialId ACCOUNT = new KeyMaterialId("account", 1);

    @Test
    void exposesOnlyPublicInventoryToApplicationAdmins() throws Exception {
        try (OrionKeyMaterial material = open(events.pkcs12Store("test-password".toCharArray()))) {
            try (KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()))) {
                modification.generateKey("acme-identity", Set.of(KeyMaterialPurpose.TLS_IDENTITY, KeyMaterialPurpose.SERVER_SIGNING), KeyMaterialAlgorithm.RSA, 2048);
                modification.generateKey(material.configurationCipherKey().alias(), Set.of(KeyMaterialPurpose.CONFIGURATION_CIPHER), KeyMaterialAlgorithm.AES, 256);
                modification.commit(VcChangeDescription.EMPTY);
            }
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
        try (OrionKeyMaterial material = open(events.pkcs12Store("test-password".toCharArray()))) {
            KeyMaterialId identity = new KeyMaterialId("identity", 1);
            try (KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()))) {
                modification.generateKey("identity", Set.of(KeyMaterialPurpose.TLS_IDENTITY), KeyMaterialAlgorithm.RSA, 2048);
                modification.commit(VcChangeDescription.EMPTY);
            }
            TestCertificateChain.Authority root =
                    TestCertificateChain.root("Public root");
            X509Certificate leaf = TestCertificateChain.leaf(
                    "orion.test", material.read(identity).keyPair(), root);
            try (KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()))) {
                modification.installCertificateChain(identity, List.of(leaf), java.util.Optional.empty());
                modification.commit(VcChangeDescription.EMPTY);
            }
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
        class ObservingStore extends TestBlobStorage {
            private OrionKeyMaterial material;

            @Override
            protected VcRevision.Stored commitSnapshot(BlobStorageOperations operation,
                    VcChangeDescription description) throws IOException {
                try {
                    assertThat(material.contains(ACCOUNT)).isFalse();
                } catch (GeneralSecurityException failure) {
                    throw new IOException(failure);
                }
                return super.commitSnapshot(operation, description);
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
            assertThat(material.revision()).isEqualTo("2");
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
        InMemoryBlobStorage store = events.pkcs12Store("test-password".toCharArray());
        try (OrionKeyMaterial material = open(store)) {
            OrionHttpResponse response = post(material, body);
            assertThat(response.status()).isEqualTo(201);
            assertThat(response.body()).isNull();
        }
        try (OrionKeyMaterial reloaded = open(store)) {
            assertThat(reloaded.read(ACCOUNT).keyPair().getPrivate().getEncoded())
                    .isEqualTo(original.getPrivate().getEncoded());
            assertThat(reloaded.read(ACCOUNT).publicKey().getEncoded())
                    .isEqualTo(original.getPublic().getEncoded());
        }
    }

    @Test
    void rejectsAnExistingAliasRegardlessOfItsPurposeWithoutReplacingTheKey() throws Exception {
        InMemoryBlobStorage store = events.pkcs12Store("test-password".toCharArray());
        try (OrionKeyMaterial material = open(store)) {
            assertThat(post(material, """
                    {"alias":"account","purpose":"ACME_ACCOUNT"}
                    """).status()).isEqualTo(201);
            byte[] publicKey = material.read(ACCOUNT).publicKey().getEncoded();
            VcRevision beforeRevision = store.read(new VcRevision.Latest(), operation -> operation.revision());
            byte[] beforeBytes = store.readBytes(new VcRevision.Latest());

            for (String purpose : List.of("ACME_ACCOUNT", "TLS_IDENTITY")) {
                String body = new ObjectMapper().writeValueAsString(
                        Map.of("alias", "account", "purpose", purpose));
                assertThat(post(material, body).status()).isEqualTo(400);
            }
            assertThat(material.read(ACCOUNT).publicKey().getEncoded()).isEqualTo(publicKey);
            assertThat(material.revision()).isEqualTo("2");
            assertThat(store.readBytes(new VcRevision.Latest())).isEqualTo(beforeBytes);
            VcRevision afterRevision = store.read(new VcRevision.Latest(), operation -> operation.revision());
            assertThat(afterRevision).isEqualTo(beforeRevision);
        }
    }

    @Test
    void rejectsInvalidNamesPurposesAndOversizedRequestsWithoutSaving() throws Exception {
        InMemoryBlobStorage store = events.pkcs12Store("test-password".toCharArray());
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
            assertThat(material.revision()).isEqualTo("1");
        }
    }

    @Test
    void sanitizesMalformedImportFailuresWithoutSaving() throws Exception {
        InMemoryBlobStorage store = events.pkcs12Store("test-password".toCharArray());
        try (OrionKeyMaterial material = open(store)) {
            OrionHttpResponse response = post(material, """
                    {"alias":"invalid","purpose":"ACME_ACCOUNT","privateKeyPem":"private-secret"}
                    """);
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body().toString()).doesNotContain("private-secret");
            assertThat(material.inventory()).isEmpty();
            assertThat(material.revision()).isEqualTo("1");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reportsFailedPublicationAndReopensOnlyTheDurableKey(boolean committed) throws Exception {
        InMemoryBlobStorage store = new TestBlobStorage() {
            @Override
            protected VcRevision.Stored commitSnapshot(BlobStorageOperations operation,
                    VcChangeDescription description) throws IOException {
                if (committed) super.commitSnapshot(operation, description);
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
            assertThat(reloaded.revision()).isEqualTo(committed ? "2" : "1");
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

    private OrionKeyMaterial open(InMemoryBlobStorage store) throws Exception {
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12("test-password".toCharArray())) {
            return OrionKeyMaterial.open(new KeyMaterialRepository(
                    store,
                    new KeyMaterialCodec(options),
                    events.events()));
        }
    }

    private class TestBlobStorage extends InMemoryBlobStorage {
        TestBlobStorage() {
            super(pro.deta.orion.test.util.OrionTestRuntime.pkcs12Content("test-password".toCharArray()),
                    false,
                    OrionAdminKeyMaterialRouteTest.this.events.events());
        }

        @Override
        protected BlobStorageOperations createOperation(VcRevision.ReadTarget revision) {
            BlobStorageOperations delegate = super.createOperation(revision);
            return new BlobStorageOperations(delegate.revision()) {
                @Override
                public byte[] readContent() { return delegate.readContent(); }

                @Override
                public void write(byte[] bytes) { delegate.write(bytes); }

                @Override
                protected VcRevision.Stored commitChanges(VcChangeDescription description) {
                    try {
                        return commitSnapshot(delegate, description);
                    } catch (IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    }
                }

                @Override
                public void close() { delegate.close(); }
            };
        }

        protected VcRevision.Stored commitSnapshot(BlobStorageOperations operation,
                VcChangeDescription description) throws IOException {
            return operation.commit(description);
        }
    }
}
