package pro.deta.orion.acl.storage;

import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.lifecycle.OrionEnableServiceSupport;
import pro.deta.orion.util.Result;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@RequiredArgsConstructor(onConstructor_ = @Inject)
public class LocalAccessControlStorage extends OrionEnableServiceSupport implements AccessControlStorage {
    private final OrionConfiguration.AccessControlConfig config;

    @Override
    public Result<AccessControlSnapshot> load() {
        Path file = aclFile();
        if (!Files.exists(file)) {
            return new Result.Failure<>(Result.FailureCode.NOT_FOUND);
        }
        try {
            return new Result.Success<>(AccessControlSnapshot.singleFile(config.getSettingsFileName(), Files.readAllBytes(file)));
        } catch (IOException e) {
            return new Result.Failure<>(Result.FailureCode.GENERAL, e.getMessage(), e);
        }
    }

    @Override
    public void save(AccessControlSnapshot snapshot, AccessControlSaveRequest request) {
        try {
            for (Map.Entry<String, byte[]> entry : snapshot.files().entrySet()) {
                Path file = aclDirectory().resolve(entry.getKey()).normalize();
                if (!file.startsWith(aclDirectory())) {
                    throw new IllegalArgumentException("ACL file escapes local ACL directory: " + entry.getKey());
                }
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
        return config.getSettingsFileName();
    }

    private Path aclFile() {
        return aclDirectory().resolve(primaryPath());
    }

    private Path aclDirectory() {
        URI uri = URI.create(config.getUrl());
        Path path;
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            path = Path.of(uri);
        } else {
            path = Path.of(config.getUrl());
        }
        return path.toAbsolutePath().normalize();
    }
}
