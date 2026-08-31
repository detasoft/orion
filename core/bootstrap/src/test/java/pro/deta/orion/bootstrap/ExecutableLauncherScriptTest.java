package pro.deta.orion.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutableLauncherScriptTest {

    private static final Path REACTOR_LAUNCHER = Path.of("core/bootstrap/src/main/launcher/orion-launcher.sh");
    private static final Path MODULE_LAUNCHER = Path.of("src/main/launcher/orion-launcher.sh");
    private static final Path REACTOR_DIST_LAUNCHER = Path.of("core/bootstrap/src/main/dist/bin/orion");
    private static final Path MODULE_DIST_LAUNCHER = Path.of("src/main/dist/bin/orion");

    @Test
    void launcherScriptSupportsDirectAndInitdCommands() throws IOException {
        Path launcher = Files.exists(REACTOR_LAUNCHER) ? REACTOR_LAUNCHER : MODULE_LAUNCHER;
        String script = Files.readString(launcher);

        assertTrue(script.startsWith("#!/bin/sh"));
        assertTrue(script.contains("### BEGIN INIT INFO"));
        assertTrue(script.contains("Default-Start:"));
        assertTrue(script.contains("Default-Stop:"));
        assertTrue(script.contains("java\" $JAVA_OPTS -jar \"$SELF\""));
        assertTrue(script.contains("exec \"$java\" $JAVA_OPTS -jar \"$SELF\" \"$@\""));
        assertFalse(script.contains("start_app()"));
        assertFalse(script.contains("stop_app()"));
        assertFalse(script.contains("case \"$command\" in"));
    }

    @Test
    void distLauncherUsesBundledRuntimeWithJavaOverride() throws IOException {
        Path launcher = Files.exists(REACTOR_DIST_LAUNCHER) ? REACTOR_DIST_LAUNCHER : MODULE_DIST_LAUNCHER;
        String script = Files.readString(launcher);

        assertTrue(script.startsWith("#!/bin/sh"));
        assertTrue(script.contains("APP_HOME=$(cd \"$SCRIPT_DIR/..\""));
        assertTrue(script.contains("BUNDLED_JAVA=$APP_HOME/runtime/bin/java"));
        assertTrue(script.contains("java=${JAVA_CMD:-$BUNDLED_JAVA}"));
        assertTrue(script.contains("exec \"$java\" $JAVA_OPTS -jar \"$APP_HOME/lib/orion.jar\" \"$@\""));
        assertFalse(script.contains("start_app()"));
        assertFalse(script.contains("stop_app()"));
    }
}
