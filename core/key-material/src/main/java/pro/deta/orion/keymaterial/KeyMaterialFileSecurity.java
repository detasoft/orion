package pro.deta.orion.keymaterial;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

final class KeyMaterialFileSecurity {
    private static final Set<PosixFilePermission> OWNER_FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> OWNER_DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> NON_OWNER_PERMISSIONS = EnumSet.of(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE);
    private static final Set<PosixFilePermission> NON_OWNER_WRITE_PERMISSIONS = EnumSet.of(
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.OTHERS_WRITE);

    private KeyMaterialFileSecurity() {
    }

    static Path normalizeLocation(Path location) {
        Path absolute = location.toAbsolutePath().normalize();
        Path fileName = absolute.getFileName();
        Path parent = absolute.getParent();
        if (fileName == null || parent == null) {
            return absolute;
        }
        Path existing = nearestExistingPath(parent);
        if (existing == null) {
            return absolute;
        }
        try {
            Path realParent = existing.toRealPath().resolve(existing.relativize(parent));
            return realParent.resolve(fileName).normalize();
        } catch (IOException e) {
            throw new IllegalArgumentException("Key material parent location cannot be resolved: " + parent, e);
        }
    }

    static void createDirectories(Path directory) throws IOException {
        if (directory == null) {
            return;
        }
        Path existing = nearestExistingPath(directory);
        if (existing != null && supportsPosix(existing)) {
            Files.createDirectories(
                    directory,
                    PosixFilePermissions.asFileAttribute(OWNER_DIRECTORY_PERMISSIONS));
        } else {
            Files.createDirectories(directory);
        }
        validatePublicationDirectory(directory);
    }

    static void validatePublicationDirectory(Path directory) throws IOException {
        validateNoSymbolicLinks(directory);
        BasicFileAttributes attributes = Files.readAttributes(
                directory,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()) {
            throw new IOException("Key material parent location is not a directory: " + directory);
        }
        if (supportsPosix(directory)) {
            Set<PosixFilePermission> permissions = Files.readAttributes(
                    directory,
                    PosixFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).permissions();
            if (!disjoint(permissions, NON_OWNER_WRITE_PERMISSIONS)) {
                throw new IOException(
                        "Key material parent directory is writable by another user: " + directory);
            }
        }
    }

    static void validateOwnerOnlyRegularFile(Path path, String description) throws IOException {
        validateNoSymbolicLinks(path);
        BasicFileAttributes attributes = Files.readAttributes(
                path,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new IOException(description + " is not a regular file: " + path);
        }
        if (supportsPosix(path)) {
            Set<PosixFilePermission> permissions = Files.readAttributes(
                    path,
                    PosixFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).permissions();
            if (!permissions.contains(PosixFilePermission.OWNER_READ)
                    || !disjoint(permissions, NON_OWNER_PERMISSIONS)) {
                throw new IOException(description + " must be readable only by its owner: " + path);
            }
        }
    }

    static byte[] readOwnerOnlyFile(Path path, String description) throws IOException {
        validateOwnerOnlyRegularFile(path, description);
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size > Integer.MAX_VALUE) {
                throw new IOException(description + " is too large: " + path);
            }
            byte[] bytes = new byte[(int) size];
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer);
                if (read < 0) {
                    byte[] truncated = Arrays.copyOf(bytes, buffer.position());
                    Arrays.fill(bytes, (byte) 0);
                    return truncated;
                }
            }
            byte[] trailingByte = new byte[1];
            int trailingBytesRead = channel.read(ByteBuffer.wrap(trailingByte));
            Arrays.fill(trailingByte, (byte) 0);
            if (trailingBytesRead > 0) {
                Arrays.fill(bytes, (byte) 0);
                throw new IOException(description + " changed while it was being read: " + path);
            }
            return bytes;
        }
    }

    static FileAttribute<?>[] ownerOnlyFileAttributes(Path parent) throws IOException {
        if (supportsPosix(parent)) {
            return new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(OWNER_FILE_PERMISSIONS)};
        }
        return new FileAttribute<?>[0];
    }

    static boolean supportsPosix(Path path) throws IOException {
        FileStore fileStore = Files.getFileStore(path);
        return fileStore.supportsFileAttributeView(PosixFileAttributeView.class);
    }

    private static Path nearestExistingPath(Path path) {
        Path current = path.toAbsolutePath().normalize();
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            current = current.getParent();
        }
        return current;
    }

    private static void validateNoSymbolicLinks(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path component : absolute) {
            current = current == null ? component : current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IOException("Key material path must not contain symbolic links: " + path);
            }
        }
    }

    private static boolean disjoint(Set<PosixFilePermission> first, Set<PosixFilePermission> second) {
        for (PosixFilePermission permission : first) {
            if (second.contains(permission)) {
                return false;
            }
        }
        return true;
    }
}
