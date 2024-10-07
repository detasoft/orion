package pro.deta.orion.util;

import lombok.Getter;
import pro.deta.orion.config.AppConfigContext;

import java.nio.file.Path;
import java.nio.file.Paths;

@Getter
public class OrionPathResolver {
    private final AppConfigContext.AppConfiguration configuration;
    private final Path basePath;

    public OrionPathResolver(AppConfigContext.AppConfiguration configuration) {
        this.configuration = configuration;

        if (OrionUtils.isNullOrEmpty(configuration.getBase())) {
            basePath = Paths.get("").toAbsolutePath();
        } else {
            if (Paths.get(configuration.getBase()).isAbsolute()) {
                basePath = Paths.get(configuration.getBase()).toAbsolutePath();
            } else {
                basePath = Paths.get("").resolve(configuration.getBase()).toAbsolutePath();
            }
        }
    }

    public Path resolve(String file) {
        if (isAbsolute(file)) {
            return Paths.get(file);
        } else {
            return basePath.resolve(file).toAbsolutePath();
        }
    }

    private boolean isAbsolute(String f) {
        return Paths.get(f).isAbsolute();
    }
}
