package pro.deta.orion;

import org.junit.jupiter.api.Test;
import pro.deta.orion.config.LocationConfigurationProvider;
import pro.deta.orion.config.schema.OrionConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ConfigurationRuntimeTest {
    @Test
    public void testConfigurationLookup() {
        LocationConfigurationProvider fcp = new LocationConfigurationProvider();
        OrionConfiguration oc = fcp.configurationLookup("classpath://config.toml");

        assertEquals("orion_root", oc.getBootstrap().getBaseDir());
        assertEquals("local:orion", oc.getBootstrap().getAccessControl().getLocation());
        assertEquals("orion.xml", oc.getBootstrap().getAccessControl().primaryPath());
        assertEquals("repos", oc.getStorage().getLocation());
        assertEquals(8000, oc.getTransport().getHttp().getPort());
    }
}
