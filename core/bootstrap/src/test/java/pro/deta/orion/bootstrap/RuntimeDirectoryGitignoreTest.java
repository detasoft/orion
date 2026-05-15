package pro.deta.orion.bootstrap;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RuntimeDirectoryGitignoreTest {

    @Test
    void ignoresDefaultRuntimeDirectory() throws IOException {
        Path gitignore = Files.exists(Path.of(".gitignore"))
                ? Path.of(".gitignore")
                : Path.of("..", "..", ".gitignore");

        assertTrue(Files.readAllLines(gitignore).contains("/orion_root/"));
    }
}
