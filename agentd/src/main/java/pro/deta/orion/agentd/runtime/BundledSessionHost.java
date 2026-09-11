package pro.deta.orion.agentd.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Set;

public final class BundledSessionHost {
    private static final Set<PosixFilePermission> OWNER_EXECUTABLE = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);

    private BundledSessionHost() {
    }

    public static Path install(Path stateDirectory) throws IOException {
        Path destination = stateDirectory.toAbsolutePath().normalize().resolve("runtime/session-host");
        URL resource = BundledSessionHost.class.getResource(resourcePath());
        if (resource == null) {
            throw new IOException("AgentD does not contain a session-host for this platform");
        }
        FileTime resourceTimestamp;
        try {
            resourceTimestamp = resourceTimestamp(resource);
        } catch (IOException failure) {
            throw new IOException("Cannot read the bundled session-host timestamp", failure);
        }
        if (isReusableAtTimestamp(destination, resourceTimestamp)) {
            return destination;
        }

        byte[] resourceChecksum;
        try {
            resourceChecksum = checksum(resource);
        } catch (IOException failure) {
            throw new IOException("Cannot checksum the bundled session-host resource", failure);
        }
        if (isExecutableFile(destination) && hasChecksum(destination, resourceChecksum)) {
            try {
                if (resourceTimestamp != null) {
                    Files.setLastModifiedTime(destination, resourceTimestamp);
                }
            } catch (IOException failure) {
                throw new IOException("Cannot refresh installed session-host metadata", failure);
            }
            if (hasChecksum(destination, resourceChecksum)) {
                return destination;
            }
        }

        install(resource, resourceTimestamp, destination);
        return destination;
    }

    private static boolean isReusableAtTimestamp(Path destination, FileTime resourceTimestamp)
            throws IOException {
        return resourceTimestamp != null
                && isExecutableFile(destination)
                && Files.getLastModifiedTime(destination, LinkOption.NOFOLLOW_LINKS)
                        .equals(resourceTimestamp);
    }

    private static boolean hasChecksum(Path destination, byte[] resourceChecksum) throws IOException {
        if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            return MessageDigest.isEqual(resourceChecksum, checksum(destination));
        } catch (IOException failure) {
            throw new IOException("Cannot checksum the installed session-host", failure);
        }
    }

    private static boolean isExecutableFile(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.isExecutable(path);
    }

    private static void install(URL resource, FileTime resourceTimestamp, Path destination)
            throws IOException {
        try {
            Files.createDirectories(destination.getParent());
        } catch (IOException failure) {
            throw new IOException("Cannot create the session-host runtime directory", failure);
        }
        Path temporary = Files.createTempFile(destination.getParent(), ".session-host-", ".tmp");
        try {
            try (InputStream input = resource.openStream()) {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            makeExecutable(temporary);
            if (resourceTimestamp != null) {
                Files.setLastModifiedTime(temporary, resourceTimestamp);
            }
            try {
                Files.move(temporary, destination,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException failure) {
                throw new IOException("Cannot atomically install the bundled session-host", failure);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void makeExecutable(Path executable) throws IOException {
        try {
            Files.setPosixFilePermissions(executable, OWNER_EXECUTABLE);
        } catch (UnsupportedOperationException ignored) {
            if (!executable.toFile().setExecutable(true, true)) {
                throw new IOException("Cannot make the bundled session-host executable");
            }
        }
    }

    private static byte[] checksum(URL resource) throws IOException {
        try (InputStream input = resource.openStream()) {
            return checksum(input);
        }
    }

    private static byte[] checksum(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return checksum(input);
        }
    }

    private static byte[] checksum(InputStream input) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JVM does not provide SHA-256", failure);
        }
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            digest.update(buffer, 0, read);
        }
        return digest.digest();
    }

    private static FileTime resourceTimestamp(URL resource) throws IOException {
        URLConnection connection = resource.openConnection();
        connection.setUseCaches(false);
        long milliseconds = connection instanceof JarURLConnection jar
                ? jar.getJarEntry().getTime() : connection.getLastModified();
        return milliseconds > 0 ? FileTime.fromMillis(milliseconds) : null;
    }

    private static String resourcePath() throws IOException {
        String architecture = switch (System.getProperty("os.arch").toLowerCase(Locale.ROOT)) {
            case "aarch64", "arm64" -> "aarch64";
            case "amd64", "x86_64" -> "x86_64";
            default -> throw new IOException("Unsupported session-host architecture");
        };
        String operatingSystem = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (operatingSystem.contains("mac")) {
            return "/META-INF/orion/native/session-host/" + architecture
                    + "-apple-darwin/session-host";
        }
        if (operatingSystem.contains("linux")) {
            return "/META-INF/orion/native/session-host/" + architecture
                    + "-unknown-linux-gnu/session-host";
        }
        if (operatingSystem.contains("windows")) {
            return "/META-INF/orion/native/session-host/" + architecture
                    + "-pc-windows-msvc/session-host.exe";
        }
        throw new IOException("Unsupported session-host operating system");
    }
}
