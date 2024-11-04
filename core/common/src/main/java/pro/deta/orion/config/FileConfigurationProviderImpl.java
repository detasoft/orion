package pro.deta.orion.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.moandjiezana.toml.Toml;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.config.schema.OrionConfiguration;

import java.io.*;

@Slf4j
public class FileConfigurationProviderImpl implements ConfigurationProvider {
    private static final String[] CONFIGURATION_LOCATION = new String[] { // order by priority
            "config.toml",
            "config.yml",
            "/etc/orion/orion.yml",
            "classpath://config.toml",
            "classpath://config.yml",
    };
    private final ObjectMapper yom = new ObjectMapper(new YAMLFactory());
    private final Toml toml = new Toml();

    @Override
    public OrionConfiguration readConfiguration() {
        return findConfiguration();
    }

    /**
     * Searches in default locations or using default config otherwise.
     *
     * @return configuration input stream
     */
    private OrionConfiguration findConfiguration() {
        for(String s : CONFIGURATION_LOCATION) {
            OrionConfiguration orionConfiguration = configurationLookup(s);
            if (orionConfiguration != null) {
                return orionConfiguration;
            }
        }
        return parseYaml(localResourceConfig("config.yml")); // take from classpath
    }

    public OrionConfiguration configurationLookup(String location) {
        InputStream inputStream = null;
        if (location == null) {
            return null;
        }
        if (location.startsWith("classpath://")) {
            inputStream = localResourceConfig(location.substring("classpath://".length()));
        }
        if (new File(location).exists()) {
            try {
                log.warn("Attempt to read configuration from {}", location);
                inputStream = new FileInputStream(location);

            } catch (Exception e) {
                log.error("Error while reading configuration from {}.", location, e);
            }
        }
        if (inputStream == null) {
            return null;
        } else {
            if (location.endsWith(".yaml") || location.endsWith(".yml")) {
                return parseYaml(inputStream);
            }
            if (location.endsWith(".toml")) {
                return parseToml(inputStream);
            }
        }
        return null;
    }

    private OrionConfiguration parseYaml(InputStream config) {
        try {
            return yom.readerFor(OrionConfiguration.class)
                    .readValue(config);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private OrionConfiguration parseToml(InputStream config) {
        return toml.read(config).to(OrionConfiguration.class);
    }

    public InputStream localResourceConfig(String name) {
        return Thread.currentThread().getContextClassLoader().getResourceAsStream(name);
    }
}
