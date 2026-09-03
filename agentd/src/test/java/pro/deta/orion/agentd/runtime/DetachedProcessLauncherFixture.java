package pro.deta.orion.agentd.runtime;

import java.nio.file.Path;
import java.util.List;

public final class DetachedProcessLauncherFixture {
    private DetachedProcessLauncherFixture() {
    }

    public static void main(String[] arguments) throws Exception {
        DetachedProcessLauncher.processBuilder().launch(
                List.of(
                        "/bin/sh",
                        "-c",
                        "sleep 0.2; printf survived > \"$1\"",
                        "orion-detached-fixture",
                        arguments[0]),
                Path.of(arguments[1]));
    }
}
