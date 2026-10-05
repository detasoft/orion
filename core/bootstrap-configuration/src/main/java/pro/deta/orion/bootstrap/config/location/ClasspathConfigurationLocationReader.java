package pro.deta.orion.bootstrap.config.location;

import pro.deta.orion.util.ResourceLocation;
import pro.deta.orion.util.ResourceScheme;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

final class ClasspathConfigurationLocationReader implements ConfigurationLocationReader {
    @Override
    public boolean supports(ResourceLocation location) {
        return location.scheme() instanceof ResourceScheme.Other other && "classpath".equals(other.value());
    }

    @Override
    public Optional<ConfigurationContent> read(ResourceLocation location) {
        String resourceName = classpathResourceName(location);
        try (InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourceName)) {
            if (input == null) {
                return Optional.empty();
            }
            return Optional.of(new ConfigurationContent(resourceName, input.readAllBytes()));
        } catch (IOException e) {
            throw new IllegalStateException("Error while reading configuration from " + location.raw(), e);
        }
    }

    private static String classpathResourceName(ResourceLocation location) {
        String resourceName = location.normalizedRelativePath();
        if (resourceName.isBlank()) {
            throw new IllegalArgumentException("Classpath configuration location must include resource name");
        }
        return resourceName;
    }
}
