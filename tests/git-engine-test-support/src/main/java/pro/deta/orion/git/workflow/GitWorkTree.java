package pro.deta.orion.git.workflow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public interface GitWorkTree extends AutoCloseable {
    GitClient client();

    Path directory();

    void add(String... pathspecs) throws Exception;

    void commit(String message) throws Exception;

    void addRemote(String name, GitRemoteRepository remote) throws Exception;

    void push(String remote, String branch) throws Exception;

    void fetch(String remote) throws Exception;

    void pull(String remote, String branch) throws Exception;

    String head() throws Exception;

    default void writeFile(String path, String content) throws IOException {
        writeFile(path, content.getBytes(StandardCharsets.UTF_8));
    }

    default void writeFile(String path, byte[] content) throws IOException {
        Path target = resolveRepositoryPath(directory(), path);
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(target, content);
    }

    default void delete(String path) throws IOException {
        Files.delete(resolveRepositoryPath(directory(), path));
    }

    default RepositorySnapshot snapshot() throws Exception {
        return RepositorySnapshot.capture(directory());
    }

    @Override
    default void close() throws Exception {
    }

    private static Path resolveRepositoryPath(Path repository, String path) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(path, "path");
        Path relative = Path.of(path);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("Git path must be relative: " + path);
        }
        for (Path part : relative) {
            if ("..".equals(part.toString())) {
                throw new IllegalArgumentException("Git path must not contain '..': " + path);
            }
        }
        return repository.resolve(relative).normalize();
    }
}
