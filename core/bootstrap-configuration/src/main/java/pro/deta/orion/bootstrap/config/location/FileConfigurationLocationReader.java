package pro.deta.orion.bootstrap.config.location;

import pro.deta.orion.util.ResourceLocation;
import pro.deta.orion.util.ResourceScheme;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

final class FileConfigurationLocationReader implements ConfigurationLocationReader {
    @Override
    public boolean supports(ResourceLocation location) {
        return switch (location.scheme()) {
            case ResourceScheme.Empty ignored -> true;
            case ResourceScheme.File ignored -> true;
            default -> false;
        };
    }

    @Override
    public Optional<ConfigurationContent> read(ResourceLocation location) {
        Path path = filePath(location);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ConfigurationContent(path.toString(), Files.readAllBytes(path)));
        } catch (IOException e) {
            throw new IllegalStateException("Error while reading configuration from " + location.raw(), e);
        }
    }

    private static Path filePath(ResourceLocation location) {
        return switch (location.scheme()) {
            case ResourceScheme.Empty ignored -> Path.of(location.raw());
            case ResourceScheme.File ignored -> Path.of(location.pathOrSchemeSpecificPart(
                    "File configuration location must include a path"));
            default -> throw new IllegalArgumentException("Unsupported file configuration location: " + location.raw());
        };
    }
}
