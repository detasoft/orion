package pro.deta.orion.cloudflare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class CloudflareIntegrationProfileTest {

    private static final String ENABLED_PROPERTY = "cloudflare.it.enabled";

    @Test
    void cloudflareMutationRequiresExplicitOptIn() {
        EnabledIfSystemProperty condition =
                IntegrationCloudflareIT.class.getAnnotation(EnabledIfSystemProperty.class);

        assertNotNull(condition);
        assertEquals(ENABLED_PROPERTY, condition.named());
        assertEquals("true", condition.matches());
    }
}
