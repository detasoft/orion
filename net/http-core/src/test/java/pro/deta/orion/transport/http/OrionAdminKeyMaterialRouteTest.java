package pro.deta.orion.transport.http;

import org.junit.jupiter.api.Test;
import pro.deta.orion.keymaterial.ConfigurationMaterialCapability;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.KeyMaterialInventoryEntry;
import pro.deta.orion.keymaterial.TrustedCertificateDescriptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.transport.http.OrionHttpRouteDefinition.Authorization.APPLICATION_ADMIN;

class OrionAdminKeyMaterialRouteTest {
    @Test
    void exposesOnlyPublicInventoryToApplicationAdmins() throws Exception {
        KeyMaterialInventoryEntry entry = new KeyMaterialInventoryEntry(
                "acme-identity", "TLS_IDENTITY", "RSA", 1, "cluster:5:orion",
                "-----BEGIN PUBLIC KEY-----\npublic\n-----END PUBLIC KEY-----", List.of());
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
        OrionAdminKeyMaterialRoute route = new OrionAdminKeyMaterialRoute(material);

        assertThat(route.definition().authorization()).isEqualTo(APPLICATION_ADMIN);
        assertThat(route.doGet(null).body()).isEqualTo(Map.of("entries", List.of(entry)));
    }
}
