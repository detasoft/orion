package pro.deta.orion.transport.http;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import pro.deta.orion.keymaterial.KeyMaterialAdministrationCapability;
import pro.deta.orion.keymaterial.ConfigurationMaterialCapability;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.KeyMaterialInventoryEntry;
import pro.deta.orion.keymaterial.TrustedCertificateDescriptor;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.transport.http.OrionHttpRouteDefinition.Authorization.APPLICATION_ADMIN;

class OrionAdminKeyMaterialRouteTest {
    @Test
    void exposesOnlyPublicInventoryToApplicationAdmins() throws Exception {
        KeyMaterialInventoryEntry entry = new KeyMaterialInventoryEntry(
                "acme-identity", "TLS_IDENTITY", "RSA", 1, "cluster:5:orion",
                "-----BEGIN PUBLIC KEY-----\npublic\n-----END PUBLIC KEY-----", "fingerprint", List.of());
        ConfigurationMaterialCapability material = new ConfigurationMaterialCapability() {
            @Override
            public void require(KeyMaterialDescriptor descriptor) {
            }

            @Override
            public void require(TrustedCertificateDescriptor descriptor) {
            }

            @Override
            public List<KeyMaterialInventoryEntry> inventory() {
                return List.of(entry);
            }
        };
        OrionAdminKeyMaterialRoute route = new OrionAdminKeyMaterialRoute(material,
                KeyMaterialAdministrationCapability.unavailable(), new ObjectMapper());

        assertThat(route.definition().authorization()).isEqualTo(APPLICATION_ADMIN);
        assertThat(route.doGet(null).body()).isEqualTo(Map.of("entries", List.of(entry)));
    }

    @Test
    void createsKeysWithoutReturningSecretsAndClearsTheImportedInput() throws Exception {
        List<char[]> received = new ArrayList<>();
        OrionAdminKeyMaterialRoute route = new OrionAdminKeyMaterialRoute(null, (alias, purpose, pem) -> {
            assertThat(alias).isEqualTo("imported");
            assertThat(purpose.name()).isEqualTo("ACME_ACCOUNT");
            assertThat(pem).containsExactly("private-secret".toCharArray());
            received.add(pem);
        }, new ObjectMapper());
        OrionHttpResponse response = route.doPost(AcmeAdministrationTest.httpRequest("POST",
                "/api/admin/key-material", """
                {"alias":"imported","purpose":"ACME_ACCOUNT","privateKeyPem":"private-secret"}
                """, AcmeAdministrationTest.admin()));
        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body()).isNull();
        assertThat(received).hasSize(1);
        assertThat(received.getFirst()).containsOnly('\0');
    }

    @Test
    void rejectsInvalidAndOversizedRequestsWithoutInvokingTheStore() throws Exception {
        OrionAdminKeyMaterialRoute route = new OrionAdminKeyMaterialRoute(null, (alias, purpose, pem) -> {
            throw new AssertionError("Invalid request reached key store");
        }, new ObjectMapper());
        for (String body : List.of("null", "{", "{\"purpose\":\"UNKNOWN\"}")) {
            assertThat(route.doPost(AcmeAdministrationTest.httpRequest("POST", "/api/admin/key-material",
                    body, AcmeAdministrationTest.admin())).status()).isEqualTo(400);
        }
        assertThat(route.doPost(AcmeAdministrationTest.httpRequest("POST", "/api/admin/key-material",
                "x".repeat(32769), AcmeAdministrationTest.admin())).status()).isEqualTo(413);
    }

    @Test
    void sanitizesImportFailuresAndClearsSecretsWhenTheStoreRejectsTheKey() throws Exception {
        List<char[]> received = new ArrayList<>();
        OrionAdminKeyMaterialRoute route = new OrionAdminKeyMaterialRoute(null, (alias, purpose, pem) -> {
            received.add(pem);
            throw new IllegalArgumentException("private-secret");
        }, new ObjectMapper());
        OrionHttpResponse response = route.doPost(AcmeAdministrationTest.httpRequest("POST",
                "/api/admin/key-material", """
                {"alias":"invalid","purpose":"ACME_ACCOUNT","privateKeyPem":"private-secret"}
                """, AcmeAdministrationTest.admin()));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().toString()).doesNotContain("private-secret");
        assertThat(received.getFirst()).containsOnly('\0');
    }
}
