package pro.deta.orion.git.parser.wire.exchange;

import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record LegacyReceiveCommandSection(
        InitialRequestData initialRequest,
        List<LegacyReceiveCommand> commands,
        Set<String> capabilities,
        GitV1Advertisement serverAdvertisement) {

    public LegacyReceiveCommandSection {
        Objects.requireNonNull(initialRequest, "initialRequest");
        Objects.requireNonNull(commands, "commands");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(serverAdvertisement, "serverAdvertisement");
        commands = List.copyOf(commands);
        capabilities = Collections.unmodifiableSet(
                new LinkedHashSet<>(capabilities));
        if (commands.isEmpty()) {
            throw new IllegalArgumentException(
                    "Receive command section must contain a command");
        }
        Set<String> refNames = new LinkedHashSet<>();
        for (LegacyReceiveCommand command : commands) {
            Objects.requireNonNull(command, "command");
            if (!refNames.add(command.refName())) {
                throw new IllegalArgumentException(
                        "Receive command section must not contain duplicate refs");
            }
        }
        for (String capability : capabilities) {
            if (Objects.requireNonNull(
                    capability,
                    "capability").isBlank()) {
                throw new IllegalArgumentException(
                        "Receive capability must not be blank");
            }
        }
    }

    public boolean requiresPack() {
        for (LegacyReceiveCommand command : commands) {
            if (command.type() != LegacyReceiveCommand.Type.DELETE) {
                return true;
            }
        }
        return false;
    }
}
