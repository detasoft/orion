package pro.deta.orion.git.compat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GitCompatibilityToolTest {
    private static final pro.deta.orion.test.util.TestEvents EVENTS =
            new pro.deta.orion.test.util.TestEvents();

    @org.junit.jupiter.api.AfterEach
    void stopTestEvents() {
        EVENTS.close();
    }

    @TempDir
    Path root;

    @Test
    void exportsANewBareRepositoryFromCommandLineArguments() throws Exception {
        Path store = root.resolve("store");
        try (NativeGitRepository ignored = NativeGitRepositoryProvider.file(store, EVENTS.manager())
                .create("demo").valueOrFailure("source repository")) {
            // An empty source still has a default HEAD and is a valid export.
        }
        Path output = root.resolve("demo.git");
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        int status = GitCompatibilityTool.run(new String[]{
                "export", "--store", store.toString(), "--repository", "demo", "--output", output.toString()
        }, new PrintStream(errors));

        assertThat(status).isZero();
        assertThat(Files.isRegularFile(output.resolve("HEAD"))).isTrue();
        assertThat(Files.isRegularFile(output.resolve("orion-export/object-map.tsv"))).isTrue();
        assertThat(errors.toString()).isEmpty();
    }
}
