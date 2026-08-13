package pro.deta.orion.git.workflow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

public record RepositorySnapshot(byte[] bytes) {
    public RepositorySnapshot {
        bytes = bytes.clone();
    }

    public static RepositorySnapshot capture(Path workTree, String head) throws IOException {
        StringBuilder output = new StringBuilder();
        output.append("HEAD\t").append(head).append('\n');
        for (Path file : workTreeFiles(workTree)) {
            appendFile(output, workTree, file);
        }
        return new RepositorySnapshot(output.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    public String text() {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static List<Path> workTreeFiles(Path workTree) throws IOException {
        try (var stream = Files.walk(workTree)) {
            List<Path> files = new ArrayList<>(stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !insideGitDirectory(workTree, path))
                    .toList());
            files.sort(Comparator.comparing(path -> gitPath(workTree.relativize(path))));
            return files;
        }
    }

    private static boolean insideGitDirectory(Path workTree, Path path) {
        Path relative = workTree.relativize(path);
        return relative.getNameCount() > 0 && ".git".equals(relative.getName(0).toString());
    }

    private static void appendFile(StringBuilder output, Path workTree, Path file) throws IOException {
        String path = gitPath(workTree.relativize(file));
        byte[] content = Files.readAllBytes(file);
        output.append("FILE\t")
                .append(hex(path.getBytes(StandardCharsets.UTF_8)))
                .append('\t')
                .append(content.length)
                .append('\t')
                .append(hex(sha256(content)))
                .append('\t')
                .append(hex(content))
                .append('\n');
    }

    private static String gitPath(Path path) {
        StringBuilder result = new StringBuilder();
        for (Path part : path) {
            if (!result.isEmpty()) {
                result.append('/');
            }
            result.append(part);
        }
        return result.toString();
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is not available", error);
        }
    }

    private static String hex(byte[] value) {
        return HexFormat.of().formatHex(value);
    }
}
