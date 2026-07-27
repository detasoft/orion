package pro.deta.orion.git.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GitProtocolClientBoundaryTest {
    @Test
    void productionModuleDoesNotDependOnJGit() throws IOException {
        Path root = repositoryRoot();
        Path module = root.resolve("core/git-protocol-client");
        List<Path> offenders = new ArrayList<>();

        collectJGitSources(module.resolve("src/main/java"), offenders);

        assertThat(offenders).isEmpty();
        assertThat(Files.readString(module.resolve("pom.xml")))
                .doesNotContain("<groupId>org.eclipse.jgit</groupId>");
    }

    private static void collectJGitSources(Path path, List<Path> offenders) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        if (Files.isRegularFile(path)) {
            if (path.getFileName().toString().endsWith(".java")
                    && Files.readString(path).contains("org.eclipse.jgit")) {
                offenders.add(path);
            }
            return;
        }

        try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
            for (Path entry : entries) {
                collectJGitSources(entry, offenders);
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
