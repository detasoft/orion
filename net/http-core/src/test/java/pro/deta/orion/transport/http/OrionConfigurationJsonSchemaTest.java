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
}
