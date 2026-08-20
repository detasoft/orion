package pro.deta.orion.schema.config;

import pro.deta.orion.schema.config.OrionConfiguration;

public interface ConfigurationProvider {
    OrionConfiguration readConfiguration();
}
