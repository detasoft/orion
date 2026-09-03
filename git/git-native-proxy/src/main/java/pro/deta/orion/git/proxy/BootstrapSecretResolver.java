package pro.deta.orion.git.proxy;

import pro.deta.orion.lifecycle.state.TestOnly;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

final class BootstrapSecretResolver {
    private final Map<String, String> environment;

    BootstrapSecretResolver(Map<String, String> environment) {
        this.environment = Map.copyOf(environment);
    }

    BootstrapSecret resolve(String name, String reference) {
        if (reference == null || !(reference.startsWith("env:") || reference.startsWith("file:"))) {
            throw new IllegalArgumentException(name + " must use env: or file:");
        }
        char[] value = reference.startsWith("env:")
                ? environmentSecret(name, reference.substring("env:".length()))
                : fileSecret(name, reference);
        try {
            return new BootstrapSecret(value);
        } finally {
            Arrays.fill(value, '\0');
        }
    }

    private char[] environmentSecret(String name, String variable) {
        if (variable.isBlank() || !environment.containsKey(variable)) {
            throw new IllegalArgumentException(name + " environment variable is not set");
        }
        return requiredValue(name, environment.get(variable).toCharArray());
    }

    private static char[] fileSecret(String name, String reference) {
        byte[] bytes = null;
        try {
            Path path = Path.of(URI.create(reference)).toAbsolutePath().normalize();
            requireProtectedFile(path, name);
            bytes = readWithoutFollowingLinks(path, name);
            return requiredValue(name, decode(bytes));
        } catch (IOException | RuntimeException error) {
            if (error instanceof IllegalArgumentException argumentError) {
                throw argumentError;
            }
            throw new IllegalArgumentException(name + " file cannot be read");
        } finally {
            if (bytes != null) {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    private static void requireProtectedFile(Path path, String name) throws IOException {
        requireIntegrityProtectedFile(path, name);
        if (!supportsPosix(path)) {
            return;
        }
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
        if (!permissions.contains(PosixFilePermission.OWNER_READ)
                || permissions.contains(PosixFilePermission.GROUP_READ)
                || permissions.contains(PosixFilePermission.GROUP_EXECUTE)
                || permissions.contains(PosixFilePermission.OTHERS_READ)
                || permissions.contains(PosixFilePermission.OTHERS_EXECUTE)) {
            throw new IllegalArgumentException(name + " file must be owner-only");
        }
    }

    static void requireIntegrityProtectedFile(Path path, String name) throws IOException {
        requireNoSymbolicLinks(path, name);
        BasicFileAttributes attributes = Files.readAttributes(
                path,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new IllegalArgumentException(name + " file must be a regular file");
        }
        Path parent = path.getParent();
        if (parent != null) {
            requireNoSymbolicLinks(parent, name);
            requireProtectedDirectory(parent, name);
        }
        if (!supportsPosix(path)) {
            return;
        }
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
        if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
            throw new IllegalArgumentException(name + " file must not be writable by other users");
        }
    }

    private static void requireProtectedDirectory(Path directory, String name) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                directory,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()) {
            throw new IllegalArgumentException(name + " file parent must be a directory");
        }
        if (!supportsPosix(directory)) {
            return;
        }
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(directory);
        if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
            throw new IllegalArgumentException(name + " file parent must not be writable by other users");
        }
    }

    private static void requireNoSymbolicLinks(Path path, String name) throws IOException {
        Path current = path.toAbsolutePath().normalize().getRoot();
        for (Path component : path.toAbsolutePath().normalize()) {
            current = current == null ? component : current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException(name + " file path must not contain symbolic links");
            }
        }
    }

    private static boolean supportsPosix(Path path) throws IOException {
        return Files.getFileStore(path).supportsFileAttributeView(PosixFileAttributeView.class);
    }

    private static byte[] readWithoutFollowingLinks(Path path, String name) throws IOException {
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(name + " file is too large");
            }
            return readSized(channel, new byte[(int) size], name);
        }
    }

    @TestOnly
    static byte[] readSized(ReadableByteChannel channel, byte[] bytes, String name) throws IOException {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer);
                if (read < 0) {
                    byte[] truncated = Arrays.copyOf(bytes, buffer.position());
                    Arrays.fill(bytes, (byte) 0);
                    return truncated;
                }
            }
            byte[] trailing = new byte[1];
            try {
                if (channel.read(ByteBuffer.wrap(trailing)) > 0) {
                    throw new IOException(name + " file changed while it was read");
                }
            } finally {
                Arrays.fill(trailing, (byte) 0);
            }
            return bytes;
        } catch (IOException | RuntimeException error) {
            Arrays.fill(bytes, (byte) 0);
            throw error;
        }
    }

    private static char[] decode(byte[] bytes) {
        CharBuffer decoded = null;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            char[] value = new char[decoded.remaining()];
            decoded.get(value);
            return trimLineEnding(value);
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("Bootstrap secret file is not valid UTF-8");
        } finally {
            if (decoded != null && decoded.hasArray()) {
                Arrays.fill(decoded.array(), '\0');
            }
        }
    }

    private static char[] trimLineEnding(char[] value) {
        int length = value.length;
        if (length > 0 && value[length - 1] == '\n') {
            length--;
            if (length > 0 && value[length - 1] == '\r') {
                length--;
            }
        }
        if (length == value.length) {
            return value;
        }
        char[] trimmed = Arrays.copyOf(value, length);
        Arrays.fill(value, '\0');
        return trimmed;
    }

    private static char[] requiredValue(String name, char[] value) {
        if (value.length == 0) {
            Arrays.fill(value, '\0');
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }
}
