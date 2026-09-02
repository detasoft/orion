package pro.deta.orion.command;

import java.util.Objects;

public record CommandRequest(String commandLine, CommandContext context) {
    public CommandRequest {
        Objects.requireNonNull(commandLine, "commandLine");
        Objects.requireNonNull(context, "context");
    }
}
