package pro.deta.orion.command;

import java.util.Objects;

public sealed interface CommandParseResult {
    record Success(ParsedCommand command) implements CommandParseResult {
        public Success {
            Objects.requireNonNull(command, "command");
        }
    }

    record Failure(CommandFailureCode code, String message, int position) implements CommandParseResult {
        public Failure {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
            if (position < 0) {
                throw new IllegalArgumentException("position must not be negative");
            }
        }
    }
}
