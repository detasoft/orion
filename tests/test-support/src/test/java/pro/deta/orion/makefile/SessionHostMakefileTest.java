package pro.deta.orion.makefile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SessionHostMakefileTest {

    private static final String PRINT_VERSION_TARGET = """

            .PHONY: print-session-host-rust-version
            print-session-host-rust-version:
            \t@printf '%s\\n' "$(SESSION_HOST_RUST_VERSION)"
            """;

    @TempDir
    Path tempDir;

    @Test
    void readsCommentedStableVersionAndIgnoresCommandLineOverride() throws Exception {
        MakeResult result = runMake(
                "commented-valid",
                """
                          [toolchain]   # canonical toolchain
                          channel   =   "1.97.0"   # exact stable pin
                        """,
                "SESSION_HOST_RUST_VERSION=9.9.9"
        );

        assertThat(result.exitCode()).as("make output:%n%s", result.output()).isZero();
        assertThat(result.output()).isEqualTo("1.97.0\n");
    }

    @Test
    void rejectsInvalidToolchainFiles() throws Exception {
        String backslash = "\\";
        List<ToolchainFixture> fixtures = List.of(
                new ToolchainFixture("wrong-section", """
                        [toolchain]
                        profile = "minimal"

                        [other]
                        channel = "1.97.0"
                        """),
                new ToolchainFixture("commented-duplicate-toolchain", """
                        [toolchain]
                        channel = "1.97.0"

                        [toolchain] # duplicate toolchain
                        profile = "minimal"
                        """),
                new ToolchainFixture("empty-channel", """
                        [toolchain]
                        channel = ""
                        """),
                new ToolchainFixture("unquoted-channel", """
                        [toolchain]
                        channel = 1.97.0
                        """),
                new ToolchainFixture("duplicate-channel", """
                        [toolchain]
                        channel = "1.97.0"
                        channel = "1.98.0"
                        """),
                new ToolchainFixture("trailing-backslash", """
                        [toolchain]
                        channel = "1.97.0%s%s"
                        """.formatted(backslash, backslash)),
                new ToolchainFixture("escaped-quote", """
                        [toolchain]
                        channel = "1.97.0%s""
                        """.formatted(backslash))
        );

        for (ToolchainFixture fixture : fixtures) {
            MakeResult result = runMake(fixture.name(), fixture.toolchain());

            assertThat(result.exitCode())
                    .as("%s:%n%s", fixture.name(), result.output())
                    .isNotZero();
            assertThat(result.output())
                    .as(fixture.name())
                    .contains("exact double-quoted [toolchain].channel");
        }
    }

    private MakeResult runMake(String name, String toolchain, String... arguments) throws Exception {
        Path root = Files.createDirectory(tempDir.resolve(name));
        Path sessionHost = Files.createDirectory(root.resolve("session-host"));
        Path makefile = root.resolve("Makefile");
        Files.copy(repositoryRoot().resolve("session-host/Makefile"), makefile);
        Files.writeString(
                makefile,
                PRINT_VERSION_TARGET,
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND
        );
        Files.writeString(
                sessionHost.resolve("rust-toolchain.toml"),
                toolchain,
                StandardCharsets.UTF_8
        );

        List<String> command = new ArrayList<>(List.of(
                "make",
                "--no-print-directory",
                "print-session-host-rust-version"
        ));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new MakeResult(process.waitFor(), output);
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("session-host/Makefile"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root");
    }

    private record ToolchainFixture(String name, String toolchain) {
    }

    private record MakeResult(int exitCode, String output) {
    }
}
