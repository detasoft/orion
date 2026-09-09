package pro.deta.orion.transport.http;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrionConfigurationJsonSchemaTest {
    @Test
    void exposesOnlyBootstrapTransportConfiguration() {
        Map<String, Object> document = new OrionConfigurationJsonSchema().document();

        Map<?, ?> rootProperties = (Map<?, ?>) document.get("properties");
        Map<?, ?> transport = (Map<?, ?>) rootProperties.get("transport");
        Map<?, ?> transportProperties = (Map<?, ?>) transport.get("properties");

        List<String> transportPropertyNames = transportProperties.keySet().stream()
                .map(String::valueOf)
                .toList();
        assertThat(transportPropertyNames)
                .containsExactlyInAnyOrder("defaultAddress", "git", "ssh", "http");
        assertThat(transport.get("additionalProperties")).isEqualTo(false);
    }

    @Test
    void describesServerSigningVerificationEntriesAsObjects() {
        Map<String, Object> document = new OrionConfigurationJsonSchema().document();

        Map<?, ?> verificationItems = itemsAt(
                document,
                "bootstrap",
                "keyMaterial",
                "serverSigning",
                "verification");
        Map<?, ?> verificationProperties = (Map<?, ?>) verificationItems.get("properties");

        assertThat(verificationItems.get("type")).isEqualTo("object");
        assertThat(verificationItems.get("additionalProperties")).isEqualTo(false);
        assertThat(((Map<?, ?>) verificationProperties.get("alias")).get("type")).isEqualTo("string");
        assertThat(((Map<?, ?>) verificationProperties.get("version")).get("type")).isEqualTo("integer");
    }

    @Test
    void retainsStringItemsForStringCollections() {
        Map<String, Object> document = new OrionConfigurationJsonSchema().document();

        Map<?, ?> trustedProxyAddressItems = itemsAt(
                document,
                "transport",
                "git",
                "packfileUri",
                "trustedProxyAddresses");

        assertThat(trustedProxyAddressItems).isEqualTo(Map.of("type", "string"));
    }

    private static Map<?, ?> itemsAt(Map<String, Object> document, String... propertyNames) {
        Map<?, ?> schema = document;
        for (String propertyName : propertyNames) {
            Map<?, ?> properties = (Map<?, ?>) schema.get("properties");
            schema = (Map<?, ?>) properties.get(propertyName);
        }
        return (Map<?, ?>) schema.get("items");
    }
}
