package pro.deta.orion.acl.storage;

import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.lifecycle.OrionEnableServiceSupport;
import pro.deta.orion.util.ResourceLocation;
import pro.deta.orion.util.Result;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

@RequiredArgsConstructor(onConstructor_ = @Inject)
public class LocalAccessControlStorage extends OrionEnableServiceSupport implements AccessControlStorage {
    private final OrionConfiguration.BootstrapAccessControlConfig config;

    @Override
    public Result<AccessControlSnapshot> load() {
        Map<String, byte[]> files = new java.util.LinkedHashMap<>();
        try {
            for (String configuredPath : config.getPaths()) {
                Path file = aclPath(configuredPath);
                if (!Files.exists(file)) {
                    return new Result.Failure<>(Result.FailureCode.NOT_FOUND);
                }
                files.put(configuredPath, Files.readAllBytes(file));
            }
            return new Result.Success<>(new AccessControlSnapshot(files, java.util.Optional.empty()));
        } catch (IOException e) {
            return new Result.Failure<>(Result.FailureCode.GENERAL, e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            return new Result.Failure<>(Result.FailureCode.GENERAL, e.getMessage(), e);
        }
    }

    @Override
    public void save(AccessControlSnapshot snapshot, AccessControlSaveRequest request) {
        try {
            for (Map.Entry<String, byte[]> entry : snapshot.files().entrySet()) {
                Path file = aclPath(entry.getKey());
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                Files.write(file, entry.getValue());
            }
        } catch (IOException e) {
            throw new RuntimeException("Cannot save local ACL snapshot", e);
        }
    }

    @Override
    public String primaryPath() {
        return config.primaryPath();
    }

    private Path aclPath(String configuredPath) {
        Path aclDirectory = aclDirectory();
        Path file = aclDirectory.resolve(configuredPath).normalize();
        if (!file.startsWith(aclDirectory)) {
            throw new IllegalArgumentException("ACL file escapes local ACL directory: " + configuredPath);
        }
        return file;
    }

    private Path aclDirectory() {
        ResourceLocation location = ResourceLocation.parse(config.getLocation(), "ACL location");
        Path path;
        if (location.hasScheme("file")) {
            path = Paths.get(location.pathOrSchemeSpecificPart("File ACL location must include a path"));
        } else if (location.hasNoScheme()) {
            path = Path.of(config.getLocation());
        } else {
            throw new IllegalArgumentException("Unsupported local ACL location: " + config.getLocation());
        }
        return path.toAbsolutePath().normalize();
    }
}
