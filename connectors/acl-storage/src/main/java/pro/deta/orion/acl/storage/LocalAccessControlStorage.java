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
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.AccessDeniedException;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Arrays;
import java.util.UUID;
import java.util.HashSet;
import java.util.Comparator;
import pro.deta.orion.lifecycle.state.TestOnly;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.TreeMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/** Publishes complete local ACL generations through one atomic pointer replacement under the configuration lock. */
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class LocalAccessControlStorage extends OrionEnableServiceSupport implements AccessControlStorage {
    private static final String CURRENT = ".orion-acl-current";
    private static final String GENERATION_PREFIX = ".orion-acl-generation-";
    private final BootstrapConfigurationSourceConfig config;

    @Override
    public Result<AccessControlSnapshot> load() {
        synchronized (LocalAccessControlStorage.class) {
            try {
                Path directory = aclDirectory();
                if (Files.notExists(directory)) {
                    return new Result.Failure<>(Result.FailureCode.NOT_FOUND);
                }
                try (FileChannel channel = openReadLock(resolvePath(directory, ".orion-configuration.lock"));
                     FileLock ignored = channel.lock(0, Long.MAX_VALUE, true)) {
                    return loadFiles();
                }
            } catch (IOException | IllegalArgumentException failure) {
                return new Result.Failure<>(Result.FailureCode.GENERAL, failure.getMessage(), failure);
            }
        }
    }

    private static FileChannel openReadLock(Path lock) throws IOException {
        try {
            return FileChannel.open(lock, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.READ,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        }
    }

    private Result<AccessControlSnapshot> loadFiles() {
        Map<String, byte[]> files = new java.util.LinkedHashMap<>();
        try {
            Path current = currentDirectory(aclDirectory());
            Map<String, Path> documents = new LinkedHashMap<>();
            for (String configuredPath : config.selectedPaths()) {
                documents.put(configuredPath, resolvePath(current, configuredPath));
            }
            for (Map.Entry<String, Path> document : documents.entrySet()) {
                String configuredPath = document.getKey();
                Path file = document.getValue();
                if (!Files.exists(file)) {
                    if (!current.equals(aclDirectory())) {
                        throw new IOException("Published ACL document is missing: " + configuredPath);
                    }
                    return new Result.Failure<>(Result.FailureCode.NOT_FOUND, configuredPath, null);
                }
                files.put(configuredPath, readDocument(file));
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
                try (FileChannel channel = FileChannel.open(resolvePath(directory, ".orion-configuration.lock"),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                     var ignored = channel.lock()) {
                    if (snapshot.version().isPresent()) {
                        Result<AccessControlSnapshot> loaded = loadFiles();
                        if (!(loaded instanceof Result.Success<AccessControlSnapshot> success)
                                || !snapshot.version().equals(success.value().version())) {
                            throw new AccessControlConcurrentUpdateException("Local configuration changed before save", null);
                        }
                    }
                    publishSnapshot(directory, snapshot.files());
                }
            } catch (IOException e) {
                throw new RuntimeException("Cannot save local ACL snapshot", e);
            }
        }
    }

    private void publishSnapshot(Path directory, Map<String, byte[]> files) throws IOException {
        List<String> paths = config.selectedPaths();
        if (new HashSet<>(paths).size() != paths.size() || !files.keySet().equals(new HashSet<>(paths))) {
            throw new IllegalArgumentException("ACL snapshot must contain exactly the configured paths");
        }
        Path current = currentDirectory(directory);
        Map<String, Path> sources = new LinkedHashMap<>();
        boolean changed = false;
        for (String path : paths) {
            Path relative = Path.of(path);
            if (relative.isAbsolute() || !relative.normalize().equals(relative) || relative.toString().isEmpty()
                    || relative.startsWith("..") || relative.getName(0).toString().startsWith(".orion-")) {
                throw new IllegalArgumentException("Invalid ACL document path: " + path);
            }
            Path source = resolvePath(current, path);
            sources.put(path, source);
            try {
                if (!Arrays.equals(readDocument(source), files.get(path))) {
                    if (!Files.isWritable(source) || !Files.isWritable(source.getParent())) {
                        throw new AccessDeniedException(source.toString());
                    }
                    changed = true;
                }
            } catch (NoSuchFileException missing) {
                changed = true;
            }
        }
        if (!changed) {
            return;
        }
        cleanGenerations(directory, current);
        try (var pointers = Files.newDirectoryStream(directory, ".orion-acl-pointer-*.tmp")) {
            for (Path abandoned : pointers) {
                Files.delete(abandoned);
            }
        }
        Path generation = directory.resolve(GENERATION_PREFIX + UUID.randomUUID());
        Files.createDirectory(generation,
                Files.getFileStore(directory).supportsFileAttributeView("posix")
                        ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------"))}
                        : new FileAttribute<?>[0]);
        Path pointer = null;
        boolean publicationAttempted = false;
        try {
            for (String path : paths) {
                Path target = resolvePath(generation, path);
                Path source = sources.get(path);
                Files.createDirectories(target.getParent());
                boolean existing = Files.exists(source, LinkOption.NOFOLLOW_LINKS);
                writeDocument(source, target, files.get(path), existing);
            }
            try (var tree = Files.walk(generation)) {
                for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        Path sourceDirectory = current.resolve(generation.relativize(path));
                        if (Files.isDirectory(sourceDirectory, LinkOption.NOFOLLOW_LINKS)) {
                            copyAccessAttributes(sourceDirectory, path);
                        }
                        forceDirectory(path);
                    }
                }
            }
            forceDirectory(directory);
            pointer = Files.createTempFile(directory, ".orion-acl-pointer-", ".tmp",
                    Files.getFileStore(directory).supportsFileAttributeView("posix")
                            ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rw-rw-rw-"))}
                            : new FileAttribute<?>[0]);
            try (FileChannel output = FileChannel.open(pointer, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(generation.getFileName().toString());
                while (bytes.hasRemaining()) {
                    output.write(bytes);
                }
                output.force(true);
            }
            publicationAttempted = true;
            publishPointer(pointer, resolvePath(directory, CURRENT));
            forceDirectory(directory);
            cleanGenerations(directory, generation);
        } catch (IOException | RuntimeException failure) {
            if (pointer != null) {
                deleteTemporary(pointer, failure);
            }
            if (!publicationAttempted) {
                try {
                    deleteGeneration(generation);
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }

    @TestOnly
    void publishPointer(Path prepared, Path current) throws IOException {
        Files.move(prepared, current, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Path currentDirectory(Path directory) throws IOException {
        String name;
        try {
            name = new String(readDocument(resolvePath(directory, CURRENT)), StandardCharsets.UTF_8);
        } catch (NoSuchFileException initialFiles) {
            return directory;
        }
        if (!name.matches(java.util.regex.Pattern.quote(GENERATION_PREFIX)
                + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IOException("Invalid ACL generation pointer");
        }
        Path generation = resolvePath(directory, name);
        if (!Files.isDirectory(generation, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Published ACL generation is missing");
        }
        return generation;
    }

    private static void cleanGenerations(Path directory, Path current) throws IOException {
        try (var children = Files.newDirectoryStream(directory, GENERATION_PREFIX + "*")) {
            for (Path child : children) {
                if (!child.equals(current)) {
                    deleteGeneration(child);
                }
            }
        }
    }

    private static void deleteGeneration(Path generation) throws IOException {
        List<Path> paths;
        try (java.util.stream.Stream<Path> tree = Files.walk(generation)) {
            paths = tree.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                    && Files.getFileStore(path).supportsFileAttributeView("posix")) {
                java.util.Set<java.nio.file.attribute.PosixFilePermission> permissions =
                        Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
                permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
                Files.setPosixFilePermissions(path, permissions);
            }
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                channel.force(true);
            }
        }
    }

    private static void writeDocument(Path source, Path target, byte[] content, boolean existing)
            throws IOException {
        FileAttribute<?>[] attributes = new FileAttribute<?>[0];
        AclFileAttributeView acl = existing ? Files.getFileAttributeView(source, AclFileAttributeView.class) : null;
        if (acl != null) {
            List<AclEntry> entries = acl.getAcl();
            attributes = new FileAttribute<?>[]{new FileAttribute<List<AclEntry>>() {
                @Override
                public String name() {
                    return "acl:acl";
                }

                @Override
                public List<AclEntry> value() {
                    return entries;
                }
            }};
        } else if (Files.getFileAttributeView(target.getParent(), PosixFileAttributeView.class) != null) {
            attributes = new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-rw-rw-"))};
        }
        try (FileChannel output = FileChannel.open(target,
                java.util.Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), attributes)) {
            ByteBuffer bytes = ByteBuffer.wrap(content);
            while (bytes.hasRemaining()) {
                output.write(bytes);
            }
            if (existing) {
                copyAccessAttributes(source, target);
                if (Arrays.equals(readDocument(source), content)) {
                    Files.setLastModifiedTime(target, Files.getLastModifiedTime(source, LinkOption.NOFOLLOW_LINKS));
                }
            }
            output.force(true);
        }
    }

    private static void deleteTemporary(Path temporary, Exception failure) {
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException | RuntimeException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    private static void copyAccessAttributes(Path source, Path target) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(source, PosixFileAttributeView.class);
        if (posix != null) {
            PosixFileAttributes original = posix.readAttributes();
            PosixFileAttributeView replacement = Files.getFileAttributeView(target, PosixFileAttributeView.class);
            PosixFileAttributes created = replacement.readAttributes();
            if (!original.owner().equals(created.owner())) {
                replacement.setOwner(original.owner());
            }
            if (!original.group().equals(created.group())) {
                replacement.setGroup(original.group());
            }
            replacement.setPermissions(original.permissions());
        } else {
            AclFileAttributeView acl = Files.getFileAttributeView(source, AclFileAttributeView.class);
            if (acl != null) {
                AclFileAttributeView replacement = Files.getFileAttributeView(target, AclFileAttributeView.class);
                if (!acl.getOwner().equals(replacement.getOwner())) {
                    replacement.setOwner(acl.getOwner());
                }
                replacement.setAcl(acl.getAcl());
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

    private static Path resolvePath(Path root, String configuredPath) {
        Path aclDirectory = root.toAbsolutePath().normalize();
        Path file = aclDirectory.resolve(configuredPath).normalize();
        if (!file.startsWith(aclDirectory)) {
            throw new IllegalArgumentException("ACL file escapes local ACL directory: " + configuredPath);
        }
        Path current = aclDirectory;
        for (Path component : aclDirectory.relativize(file)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Symbolic links are not allowed below the local ACL directory");
            }
        }
        return file;
    }

    private static byte[] readDocument(Path file) throws IOException {
        if (!Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
            throw new IOException("ACL document must be a regular file: " + file);
        }
        try (java.io.InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return input.readAllBytes();
        }
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
