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
        return resolve(configuration.getWorkDir());
    }

    public Path getBaseDir() {
        return baseDir.value();
    }

    private boolean isAbsolute(String f) {
        return Paths.get(f).isAbsolute();
    }

    public Path getGitStoragePath() {
        return resolve(getConfiguration().getGit().getStoragePath());
    }

    private static Path calcBaseDirPath(OrionConfiguration configuration) {
        Path path = Paths.get("");
        if (OrionUtils.isNullOrEmpty(configuration.getBaseDir())) {
            return path.toAbsolutePath();
        } else {
            if (Paths.get(configuration.getBaseDir()).isAbsolute()) {
                return Paths.get(configuration.getBaseDir()).toAbsolutePath();
            } else {
                return path.resolve(configuration.getBaseDir()).toAbsolutePath();
            }
        }
    }
}
