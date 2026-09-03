package pro.deta.orion;

import org.junit.jupiter.api.Test;
import pro.deta.orion.config.LocationConfigurationProvider;
import pro.deta.orion.schema.config.OrionConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConfigurationRuntimeTest {
    @Test
    public void testConfigurationLookup() {
        assertShippedConfiguration("config.toml");
        assertShippedConfiguration("config.yml");
    }

    private static void assertShippedConfiguration(String resource) {
        LocationConfigurationProvider fcp = new LocationConfigurationProvider();
        OrionConfiguration oc = fcp.configurationLookup("classpath://" + resource);

        assertEquals("orion_root", oc.getBootstrap().getBaseDir());
        assertEquals("local:orion", oc.getBootstrap().getAccessControl().getLocation());
        assertEquals("refs/heads/main", oc.getBootstrap().getAccessControl().selectedRef());
        assertEquals("orion.xml", oc.getBootstrap().getAccessControl().getPath());
        assertEquals("local:orion", oc.getBootstrap().getKeyMaterial().getLocation());
        assertEquals("refs/heads/main", oc.getBootstrap().getKeyMaterial().selectedRef());
        assertEquals("material.p12", oc.getBootstrap().getKeyMaterial().getPath());
        assertEquals("env:ORION_KEY_MATERIAL_PASSWORD", oc.getBootstrap().getKeyMaterial().getPassword());
        assertFalse(oc.getBootstrap().getKeyMaterial().isCreateIfMissing());
        assertEquals("orion", oc.getBootstrap().getKeyMaterial().getClusterId());
        assertEquals("repos", oc.getStorage().getLocation());
        assertTrue(oc.getTransport().getGit().isEnabled());
        assertEquals(9419, oc.getTransport().getGit().getPort());
        assertEquals(8000, oc.getTransport().getHttp().getPort());
    }
}
