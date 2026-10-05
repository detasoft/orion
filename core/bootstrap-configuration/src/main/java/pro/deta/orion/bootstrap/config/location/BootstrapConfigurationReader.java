package pro.deta.orion.bootstrap.config.location;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.moandjiezana.toml.Toml;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.util.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class BootstrapConfigurationReader {
    private static final String[] DEFAULT_CONFIGURATION_LOCATIONS = new String[] { // order by priority
            "config.toml",
            "config.yml",
            "/etc/orion/orion.yml",
            "classpath://config.toml",
            "classpath://config.yml",
    };
    private final ObjectMapper yom = new ObjectMapper(new YAMLFactory());
    private final Toml toml = new Toml();
    private final String[] configurationLocations;
    private final boolean explicitConfigurationLocation;
    private final List<ConfigurationLocationReader> readers;

    public BootstrapConfigurationReader() {
        this(DEFAULT_CONFIGURATION_LOCATIONS, false);
    }

    public BootstrapConfigurationReader(String configurationLocation) {
        this(new String[]{requiredLocation(configurationLocation)}, true);
    }

    BootstrapConfigurationReader(String[] configurationLocations, boolean explicitConfigurationLocation) {
        this(configurationLocations, explicitConfigurationLocation, defaultReaders());
    }

    BootstrapConfigurationReader(
            String[] configurationLocations,
            boolean explicitConfigurationLocation,
            List<ConfigurationLocationReader> readers) {
        this.configurationLocations = configurationLocations.clone();
        this.explicitConfigurationLocation = explicitConfigurationLocation;
        this.readers = List.copyOf(readers);
    }

    public BootstrapConfiguration readConfiguration() {
        return findConfiguration();
    }

    private BootstrapConfiguration findConfiguration() {
        for (String location : configurationLocations) {
            BootstrapConfiguration orionConfiguration = configurationLookup(location);
            if (orionConfiguration != null) {
                return orionConfiguration;
            }
        }
        if (explicitConfigurationLocation) {
            throw new IllegalArgumentException(
                    "Configuration location not found or unsupported: " + configurationLocations[0]);
        }
        return parseYaml(localResourceConfig("config.yml"));
    }

    private BootstrapConfiguration configurationLookup(String location) {
        if (location == null) {
            return null;
        }
        ResourceLocation resourceLocation = ResourceLocation.parse(location, "Configuration location");
        for (ConfigurationLocationReader reader : readers) {
            if (!reader.supports(resourceLocation)) {
                continue;
            }
            Optional<ConfigurationContent> content = reader.read(resourceLocation);
            if (content.isEmpty()) {
                return null;
            }
            return parse(content.get());
        }
        return null;
    }

    private InputStream localResourceConfig(String name) {
        return Thread.currentThread().getContextClassLoader().getResourceAsStream(name);
    }

    private BootstrapConfiguration parse(ConfigurationContent content) {
        String sourceName = content.sourceName().toLowerCase(Locale.ROOT);
        try (InputStream input = new ByteArrayInputStream(content.content())) {
            if (sourceName.endsWith(".yaml") || sourceName.endsWith(".yml")) {
                return parseYaml(input);
            }
            if (sourceName.endsWith(".toml")) {
                return parseToml(input);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot close configuration input stream", e);
        }
        throw new IllegalArgumentException("Unsupported configuration format: " + content.sourceName());
    }

    private BootstrapConfiguration parseYaml(InputStream config) {
        try {
            return yom.readerFor(BootstrapConfiguration.class)
                    .readValue(config);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private BootstrapConfiguration parseToml(InputStream config) {
        return toml.read(config).to(BootstrapConfiguration.class);
    }

    private static List<ConfigurationLocationReader> defaultReaders() {
        return List.of(
                new FileConfigurationLocationReader(),
                new ClasspathConfigurationLocationReader(),
                new S3ConfigurationLocationReader(new AwsS3ObjectClient()));
    }

    private static String requiredLocation(String configurationLocation) {
        if (configurationLocation == null || configurationLocation.isBlank()) {
            throw new IllegalArgumentException("Configuration location must not be blank");
        }
        return configurationLocation;
    }
}
