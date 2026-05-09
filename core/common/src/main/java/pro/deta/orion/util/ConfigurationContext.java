package pro.deta.orion.util;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.config.schema.OrionConfiguration;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.net.URI;
import java.net.URISyntaxException;
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

    public Path getGitStoragePath() {
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
        if (OrionUtils.isNullOrEmpty(location)) {
            throw new IllegalArgumentException("Storage location must not be empty");
        }

        URI uri = parseUri(location);
        String scheme = uri.getScheme();
        if (scheme == null) {
            return resolve(location);
        }
        if (!"file".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Unsupported repository storage location: " + location);
        }
        if (uri.getPath() != null && !uri.getPath().isBlank()) {
            Path path = Paths.get(uri.getPath());
            return path.isAbsolute() ? path.normalize() : Paths.get("").resolve(path).toAbsolutePath().normalize();
        }
        String schemeSpecificPart = uri.getSchemeSpecificPart();
        if (OrionUtils.isNullOrEmpty(schemeSpecificPart)) {
            throw new IllegalArgumentException("File storage location must include a path");
        }
        Path path = Paths.get(schemeSpecificPart);
        return path.isAbsolute() ? path.normalize() : Paths.get("").resolve(path).toAbsolutePath().normalize();
    }

    private static URI parseUri(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid storage location: " + value, e);
        }
    }
}
