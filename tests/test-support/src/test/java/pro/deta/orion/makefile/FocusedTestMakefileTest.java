package pro.deta.orion.makefile;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FocusedTestMakefileTest {

    private static final String TEST_MODULE = "core/common";
    private static final String TEST_LOCATOR = "LogInitializerTest#initializesLogging";
    private static final String MAVEN_ARGUMENT_PRINTER = "MAVEN=printf '%s\\n'";

    @Test
    void namedAndPositionalArgumentsRunEquivalentMavenCommand() throws Exception {
        MakeResult named = runMake(
                "run-test",
                "MODULE=" + TEST_MODULE,
                "TEST=" + TEST_LOCATOR,
                MAVEN_ARGUMENT_PRINTER
        );
        MakeResult positional = runMake(
                "run-test",
                TEST_MODULE,
                TEST_LOCATOR,
                MAVEN_ARGUMENT_PRINTER
        );

        assertThat(named.exitCode()).as("named output:%n%s", named.output()).isZero();
        assertThat(positional.exitCode()).as("positional output:%n%s", positional.output()).isZero();
        assertThat(positional.output()).isEqualTo(named.output());
        assertThat(named.output()).isEqualTo("""
                test
                -Pdev
                -T
                4
                -q
                -pl
                core/common
                -am
                -Dtest=LogInitializerTest#initializesLogging
                -Dsurefire.failIfNoSpecifiedTests=false
                """);
    }

    @Test
    void preservesNestedClassTestLocators() throws Exception {
        String locator = "Outer$InnerTest";

        MakeResult named = runMake(
                "run-test",
                "MODULE=" + TEST_MODULE,
                "TEST=" + locator,
                MAVEN_ARGUMENT_PRINTER
        );
        MakeResult positional = runMake(
                "run-test",
                TEST_MODULE,
                locator,
                MAVEN_ARGUMENT_PRINTER
        );

        assertThat(named.exitCode()).as("named output:%n%s", named.output()).isZero();
        assertThat(positional.exitCode()).as("positional output:%n%s", positional.output()).isZero();
        assertThat(named.output()).contains("-Dtest=" + locator);
        assertThat(positional.output()).contains("-Dtest=" + locator);
    }

    @Test
    void positionalArgumentsAcceptMavenArtifactSelectors() throws Exception {
        MakeResult result = runMake(
                "run-test",
                ":common",
                "LogInitializerTest",
                MAVEN_ARGUMENT_PRINTER
        );

        assertThat(result.exitCode()).as("make output:%n%s", result.output()).isZero();
        assertThat(result.output()).contains("-pl\n:common\n");
    }

    @Test
    void rejectsPositionalArgumentsThatCollideWithMakeGoals() throws Exception {
        MakeResult result = runMake(
                "run-test",
                TEST_MODULE,
                "test",
                MAVEN_ARGUMENT_PRINTER
        );

        assertThat(result.exitCode()).as("make output:%n%s", result.output()).isNotZero();
        assertThat(result.output()).contains("use MODULE=... TEST=... instead");
    }

    @Test
    void rejectsMissingExtraAndMixedArguments() throws Exception {
        List<List<String>> invalidArguments = List.of(
                List.of("run-test", MAVEN_ARGUMENT_PRINTER),
                List.of("run-test", "MODULE=" + TEST_MODULE, MAVEN_ARGUMENT_PRINTER),
                List.of("run-test", TEST_MODULE, TEST_LOCATOR, "extra", MAVEN_ARGUMENT_PRINTER),
                List.of(
                        "run-test",
                        TEST_MODULE,
                        TEST_LOCATOR,
                        "MODULE=" + TEST_MODULE,
                        "TEST=" + TEST_LOCATOR,
                        MAVEN_ARGUMENT_PRINTER
                )
        );

        for (List<String> arguments : invalidArguments) {
            MakeResult result = runMake(arguments.toArray(String[]::new));

            assertThat(result.exitCode()).as("make output:%n%s", result.output()).isNotZero();
            assertThat(result.output()).contains("Usage: make run-test MODULE=<module> TEST='<test-locator>'");
        }
    }

    @Test
    void doesNotHideUnknownGoals() throws Exception {
        MakeResult result = runMake("definitely-not-a-target", MAVEN_ARGUMENT_PRINTER);

        assertThat(result.exitCode()).as("make output:%n%s", result.output()).isNotZero();
        assertThat(result.output())
                .contains("No rule to make target")
                .contains("definitely-not-a-target");
    }

    private MakeResult runMake(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                "make",
                "--no-print-directory",
                "--silent"
        ));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(repositoryRoot().toFile())
                .redirectErrorStream(true);
        builder.environment().remove("MODULE");
        builder.environment().remove("TEST");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new MakeResult(process.waitFor(), output);
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("Makefile"))
                    && Files.isRegularFile(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root");
    }

    private record MakeResult(int exitCode, String output) {
    }
}
