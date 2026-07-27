package pro.deta.orion.git.parser.wire.receivepack;

import pro.deta.orion.git.parser.wire.capability.GitCapabilitySet;

import java.util.List;
import java.util.Objects;

public record ReceivePackCommandSection(
        List<ReceivePackCommand> commands,
        GitCapabilitySet clientCapabilities) {

    public ReceivePackCommandSection {
        Objects.requireNonNull(commands, "commands");
        Objects.requireNonNull(clientCapabilities, "clientCapabilities");
        commands = List.copyOf(commands);
    }
}
