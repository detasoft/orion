package pro.deta.orion.acl.storage;

import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import pro.deta.orion.lifecycle.OrionEnableServiceSupport;
import pro.deta.orion.schema.config.BootstrapConfigurationSourceConfig;
import pro.deta.orion.util.ResourceLocation;
import pro.deta.orion.util.ResourceScheme;
import pro.deta.orion.util.Result;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.nio.file.NoSuchFileException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.TreeMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

@RequiredArgsConstructor(onConstructor_ = @Inject)
public class LocalAccessControlStorage extends OrionEnableServiceSupport implements AccessControlStorage {
    private final BootstrapConfigurationSourceConfig config;

    @Override
    public Result<AccessControlSnapshot> load() {
        synchronized (LocalAccessControlStorage.class) {
            return loadFiles();
        }
    }

    private Result<AccessControlSnapshot> loadFiles() {
        Map<String, byte[]> files = new java.util.LinkedHashMap<>();
        try {
            for (String configuredPath : config.selectedPaths()) {
                Path file = aclPath(configuredPath);
                if (!Files.exists(file)) {
                    return new Result.Failure<>(Result.FailureCode.NOT_FOUND);
                }
                files.put(configuredPath, Files.readAllBytes(file));
            }
            return new Result.Success<>(new AccessControlSnapshot(files, Optional.of(version(files))));
        } catch (IOException e) {
            return new Result.Failure<>(Result.FailureCode.GENERAL, e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            return new Result.Failure<>(Result.FailureCode.GENERAL, e.getMessage(), e);
        }
    }

    @Override
    public void save(AccessControlSnapshot snapshot, AccessControlSaveRequest request) {
        synchronized (LocalAccessControlStorage.class) {
            try {
                Path directory = aclDirectory();
                Files.createDirectories(directory);
                try (FileChannel channel = FileChannel.open(directory.resolve(".orion-configuration.lock"),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     var ignored = channel.lock()) {
                    if (snapshot.version().isPresent()) {
                        Result<AccessControlSnapshot> loaded = load();
                        if (!(loaded instanceof Result.Success<AccessControlSnapshot> success)
                                || !snapshot.version().equals(success.value().version())) {
                            throw new AccessControlConcurrentUpdateException("Local configuration changed before save", null);
                        }
                    }
                    Map<Path, byte[]> changed = new LinkedHashMap<>();
                    for (Map.Entry<String, byte[]> entry : snapshot.files().entrySet()) {
                        changed.put(aclPath(entry.getKey()), entry.getValue());
                    }
                    Iterator<Map.Entry<Path, byte[]>> pending = changed.entrySet().iterator();
                    while (pending.hasNext()) {
                        Map.Entry<Path, byte[]> entry = pending.next();
                        try {
                            if (Arrays.equals(Files.readAllBytes(entry.getKey()), entry.getValue())) {
                                pending.remove();
                            }
                        } catch (NoSuchFileException missing) {
                            // A new document still needs to be written.
                        }
                    }
                    for (Map.Entry<Path, byte[]> entry : changed.entrySet()) {
                        Path file = entry.getKey();
                        if (file.getParent() != null) {
                            Files.createDirectories(file.getParent());
                        }
                        Files.write(file, entry.getValue());
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("Cannot save local ACL snapshot", e);
            }
        }
    }

    private static String version(Map<String, byte[]> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (var entry : new TreeMap<>(files).entrySet()) {
                byte[] path = entry.getKey().getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(path.length).array());
                digest.update(path);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(entry.getValue().length).array());
                digest.update(entry.getValue());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    @Override
    public String primaryPath() {
        return config.getPath();
    }

    @Override
    public boolean createIfMissing() {
        return config.isCreateDefaultIfMissing();
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
        Path path = switch (location.scheme()) {
            case ResourceScheme.File ignored -> Paths.get(
                    location.pathOrSchemeSpecificPart("File ACL location must include a path"));
            case ResourceScheme.Empty ignored -> Path.of(config.getLocation());
            case ResourceScheme.Local ignored -> Path.of(location.normalizedRelativePath());
            default -> throw new IllegalArgumentException("Unsupported local ACL location: " + config.getLocation());
        };
        return path.toAbsolutePath().normalize();
    }
}
