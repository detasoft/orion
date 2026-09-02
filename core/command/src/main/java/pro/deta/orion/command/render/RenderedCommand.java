package pro.deta.orion.command.render;

import java.util.Objects;

public record RenderedCommand(String stdout, String stderr, int exitCode) {
    public RenderedCommand {
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
    }
}
