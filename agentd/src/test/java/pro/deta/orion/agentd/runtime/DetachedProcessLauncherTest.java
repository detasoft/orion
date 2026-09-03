package pro.deta.orion.agentd.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DetachedProcessLauncherTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void redirectedChildSurvivesTheLaunchingJvmExit() throws Exception {
        Path survived = temporaryDirectory.resolve("survived");
        Path log = temporaryDirectory.resolve("host.log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process launcher = new ProcessBuilder(List.of(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                DetachedProcessLauncherFixture.class.getName(),
                survived.toString(),
                log.toString()))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start();

        assertThat(launcher.waitFor(2, TimeUnit.SECONDS)).isTrue();
        assertThat(launcher.exitValue()).isZero();
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!Files.exists(survived) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(survived).hasContent("survived");
    }
}
