package pro.deta.orion.util;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.config.schema.OrionConfiguration;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

@Slf4j
@Singleton
@Getter
public class ConfigurationContext {
    private final OrionConfiguration configuration;
    private final LazySupplier<Path> baseDir;
    private final Map<String, String> environment;

    @Inject
    public ConfigurationContext(OrionConfiguration configuration) {
        this(configuration, System.getenv());
    }

    ConfigurationContext(OrionConfiguration configuration, Map<String, String> environment) {
        this.configuration = configuration;
        this.environment = Map.copyOf(environment);
        this.baseDir = new LazySupplier<>(() -> {
            Path baseDir = null;
            try {
                baseDir = calcBaseDirPath(configuration, this.environment);
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

    private static Path calcBaseDirPath(OrionConfiguration configuration, Map<String, String> environment) {
        String configuredBaseDir = configuration.getBootstrap().getBaseDir();
        if (OrionUtils.isNullOrEmpty(configuredBaseDir)) {
            return workingDirectory();
        }

        return resolvePath(configuredBaseDir, environment, "Bootstrap baseDir");
    }

    private Path resolveStorageLocation(String location) {
        ResourceLocation resourceLocation = ResourceLocation.parse(location, "Storage location");
        return switch (resourceLocation.scheme()) {
            case ResourceScheme.Empty ignored -> resolve(location);
            case ResourceScheme.File ignored -> {
                Path path = pathFromValue(
                        resourceLocation.pathOrSchemeSpecificPart("File storage location must include a path"),
                        environment,
                        "File storage location");
                yield normalizeAgainstWorkingDirectory(path);
            }
            case ResourceScheme.Other other when "env".equals(other.value()) -> {
                Path path = pathFromValue(resourceLocation.raw(), environment, "Storage location");
                yield normalizeAgainstWorkingDirectory(path);
            }
            default -> throw new IllegalArgumentException("Unsupported repository storage location: " + location);
        };
    }

    private static Path resolvePath(String value, Map<String, String> environment, String name) {
        return normalizeAgainstWorkingDirectory(pathFromValue(value, environment, name));
    }

    private static Path pathFromValue(String value, Map<String, String> environment, String name) {
        if (value.startsWith("env:")) {
            return pathFromEnvironment(value.substring("env:".length()), environment);
        }
        return Paths.get(value);
    }

    private static Path pathFromEnvironment(String expression, Map<String, String> environment) {
        if (expression.isBlank()) {
            throw new IllegalArgumentException("Environment variable name must not be empty");
        }

        int separator = expression.indexOf('/');
        String variable = separator >= 0 ? expression.substring(0, separator) : expression;
        String remainder = separator >= 0 ? expression.substring(separator + 1) : "";
        String value = environment.get(variable);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Environment variable " + variable + " is not set");
        }

        Path path = Paths.get(value);
        if (!remainder.isBlank()) {
            path = path.resolve(remainder);
        }
        return path;
    }

    private static Path normalizeAgainstWorkingDirectory(Path path) {
        return path.isAbsolute()
                ? path.toAbsolutePath().normalize()
                : workingDirectory().resolve(path).toAbsolutePath().normalize();
    }

    private static Path workingDirectory() {
        return Paths.get("").toAbsolutePath().normalize();
    }
}
