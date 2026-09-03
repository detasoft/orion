package pro.deta.orion.command;

import java.util.List;
import java.util.Objects;

public record CommandLocation(CommandPath path, CommandNode node, List<Object> resources) {
    public CommandLocation {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(resources, "resources");
        if (!path.absolute()) {
            throw new IllegalArgumentException("location path must be absolute");
        }
        resources = List.copyOf(resources);
    }
}
