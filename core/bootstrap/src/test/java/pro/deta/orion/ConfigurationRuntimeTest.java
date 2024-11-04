package pro.deta.orion;

import org.junit.jupiter.api.Test;
import pro.deta.orion.config.FileConfigurationProviderImpl;
import pro.deta.orion.config.schema.OrionConfiguration;

public class ConfigurationRuntimeTest {
    @Test
    public void testConfigurationLookup() {
        FileConfigurationProviderImpl fcp = new FileConfigurationProviderImpl();
        OrionConfiguration oc = fcp.configurationLookup("classpath://config.toml");
        System.out.println(oc.toString());
    }
}
