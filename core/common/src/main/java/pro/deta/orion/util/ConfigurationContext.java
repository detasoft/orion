package pro.deta.orion.util;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.config.schema.OrionConfiguration;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Singleton
@Getter
public class ConfigurationContext {
    private final OrionConfiguration configuration;
    private final LazySupplier<Path> baseDir;

    @Inject
    public ConfigurationContext(OrionConfiguration configuration) {
        this.configuration = configuration;
        this.baseDir = new LazySupplier<>(() -> {
            Path baseDir = null;
            try {
                baseDir = calcBaseDirPath(configuration);
                return baseDir;
            } finally {
                log.info("baseDir: {}", baseDir);
            }
        });
    }

    public Path resolve(String file) {
        if (isAbsolute(file)) {
            return Paths.get(file);
        } else {
            return getBaseDir().resolve(file).toAbsolutePath();
        }
    }

    public Path getWorkDir() {
        return resolve(configuration.getBootstrap().getWorkDir());
    }

    public Path getBaseDir() {
        return baseDir.value();
    }

    private boolean isAbsolute(String f) {
        return Paths.get(f).isAbsolute();
    }

    public Path getFileGitStoragePath() {
        return resolveStorageLocation(configuration.getStorage().getLocation());
    }

    private static Path calcBaseDirPath(OrionConfiguration configuration) {
        Path path = Paths.get("");
        String configuredBaseDir = configuration.getBootstrap().getBaseDir();
        if (OrionUtils.isNullOrEmpty(configuredBaseDir)) {
            return path.toAbsolutePath();
        } else {
            if (Paths.get(configuredBaseDir).isAbsolute()) {
                return Paths.get(configuredBaseDir).toAbsolutePath();
            } else {
                return path.resolve(configuredBaseDir).toAbsolutePath();
            }
        }
    }

    private Path resolveStorageLocation(String location) {
        ResourceLocation resourceLocation = ResourceLocation.parse(location, "Storage location");
        if (resourceLocation.hasNoScheme()) {
            return resolve(location);
        }
        if (!resourceLocation.hasScheme("file")) {
            throw new IllegalArgumentException("Unsupported repository storage location: " + location);
        }
        Path path = Paths.get(resourceLocation.pathOrSchemeSpecificPart("File storage location must include a path"));
        return path.isAbsolute() ? path.normalize() : Paths.get("").resolve(path).toAbsolutePath().normalize();
    }
}
