package pro.deta.orion.git.client;

import java.util.List;
import java.util.Objects;

public record GitReceivePackRequest(
        List<Command> commands,
        GitPackSource packSource,
        boolean atomic) {

    public GitReceivePackRequest(
            List<Command> commands,
            GitPackSource packSource) {
        this(commands, packSource, false);
    }

    public GitReceivePackRequest {
        commands = List.copyOf(Objects.requireNonNull(commands, "commands"));
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("commands must not be empty");
        }
        Objects.requireNonNull(packSource, "packSource");
    }

    public record Command(
            String oldObjectId,
            String newObjectId,
            String refName) {
        public Command {
            GitClientValidation.requireObjectId(oldObjectId, "oldObjectId");
            GitClientValidation.requireObjectId(newObjectId, "newObjectId");
            GitClientValidation.requireRefName(refName, "refName");
            if (oldObjectId.equalsIgnoreCase(GitClientValidation.NULL_ID)
                    && newObjectId.equalsIgnoreCase(
                            GitClientValidation.NULL_ID)) {
                throw new IllegalArgumentException(
                        "oldObjectId and newObjectId must not both be zero");
            }
        }
    }
}
