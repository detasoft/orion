package pro.deta.orion.provisioning;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record RuntimeArtifact(Path source, String remoteName, String sha256) {
    public RuntimeArtifact {
        if (source == null || !Files.isRegularFile(source)) {
            throw new IllegalArgumentException("Runtime artifact must be a regular file");
        }
        source = source.toAbsolutePath().normalize();
        if (remoteName == null || !remoteName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Runtime artifact name is invalid");
        }
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Runtime artifact SHA-256 is invalid");
        }
    }

    public static RuntimeArtifact from(Path source, String remoteName) throws IOException {
        return new RuntimeArtifact(source, remoteName, digest(source));
    }

    public static RuntimeArtifact withExpectedDigest(Path source, String remoteName, String sha256) {
        return new RuntimeArtifact(source, remoteName, sha256);
    }

    private static String digest(Path source) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        try (InputStream input = Files.newInputStream(source)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
