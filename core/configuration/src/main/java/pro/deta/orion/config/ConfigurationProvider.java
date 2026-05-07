package pro.deta.orion.config;

import pro.deta.orion.config.schema.OrionConfiguration;

public interface ConfigurationProvider {
    OrionConfiguration readConfiguration();
}
