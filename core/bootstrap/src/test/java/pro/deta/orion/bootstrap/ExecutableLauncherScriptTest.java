package pro.deta.orion.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutableLauncherScriptTest {

    private static final Path REACTOR_LAUNCHER = Path.of("core/bootstrap/src/main/launcher/orion-launcher.sh");
    private static final Path MODULE_LAUNCHER = Path.of("src/main/launcher/orion-launcher.sh");

    @Test
    void launcherScriptSupportsDirectAndInitdCommands() throws IOException {
        Path launcher = Files.exists(REACTOR_LAUNCHER) ? REACTOR_LAUNCHER : MODULE_LAUNCHER;
        String script = Files.readString(launcher);

        assertTrue(script.startsWith("#!/bin/sh"));
        assertTrue(script.contains("### BEGIN INIT INFO"));
        assertTrue(script.contains("Default-Start:"));
        assertTrue(script.contains("Default-Stop:"));
        assertTrue(script.contains("java\" $JAVA_OPTS -jar \"$SELF\""));

        assertTrue(script.contains("run)"));
        assertTrue(script.contains("start)"));
        assertTrue(script.contains("stop)"));
        assertTrue(script.contains("status)"));
        assertTrue(script.contains("restart)"));
    }
}
