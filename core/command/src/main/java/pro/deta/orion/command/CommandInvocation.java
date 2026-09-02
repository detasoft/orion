package pro.deta.orion.command;

import java.util.List;
import java.util.Objects;

public record CommandInvocation(
        CommandContext context,
        CommandPath path,
        String action,
        CommandArguments arguments,
        List<Object> resolvedResources) {
    public CommandInvocation {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(resolvedResources, "resolvedResources");
        resolvedResources = List.copyOf(resolvedResources);
    }
}
