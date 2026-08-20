package pro.deta.orion.git;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JGit runtime boundary")
class JGitRuntimeBoundaryTest {
    private static final Path ALLOWED_INSTALLER = Path.of(
            "core/git-engine/src/main/java/pro/deta/orion/git/OrionJGitRuntime.java");

    @Test
    @DisplayName("SystemReader can be installed only by OrionJGitRuntime")
    void systemReaderCanBeInstalledOnlyByOrionJGitRuntime() throws IOException {
        Path root = repositoryRoot();
        String forbiddenCall = "SystemReader" + ".setInstance(";
        List<Path> offenders = new ArrayList<>();

        for (Path sourceFile : javaSources(root)) {
            String source = Files.readString(sourceFile);
            if (source.contains(forbiddenCall) && !root.relativize(sourceFile).equals(ALLOWED_INSTALLER)) {
                offenders.add(root.relativize(sourceFile));
            }
        }

        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("default JGit SystemReader is never restored explicitly")
    void defaultJGitSystemReaderIsNeverRestoredExplicitly() throws IOException {
        Path root = repositoryRoot();
        String defaultRestore = "setInstance(" + "null";
        List<Path> offenders = new ArrayList<>();

        for (Path sourceFile : javaSources(root)) {
            String source = Files.readString(sourceFile);
            if (source.contains(defaultRestore)) {
                offenders.add(root.relativize(sourceFile));
            }
        }

        assertThat(offenders).isEmpty();
    }

    private static List<Path> javaSources(Path root) throws IOException {
        List<Path> sources = new ArrayList<>();
        collectJavaSources(root.resolve("core"), sources);
        collectJavaSources(root.resolve("net"), sources);
        collectJavaSources(root.resolve("tests"), sources);
        collectJavaSources(root.resolve("integration"), sources);
        return sources;
    }

    private static void collectJavaSources(Path path, List<Path> sources) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        if (Files.isRegularFile(path)) {
            if (path.getFileName().toString().endsWith(".java")) {
                sources.add(path);
            }
            return;
        }

        String name = path.getFileName().toString();
        if ("target".equals(name) || ".git".equals(name)) {
            return;
        }

        try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
            for (Path entry : entries) {
                collectJavaSources(entry, sources);
            }
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve(".root")) && Files.exists(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root");
    }
}
