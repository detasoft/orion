package pro.deta.orion.git.parser.wire;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record GitProtocolV2Request(
        String command,
        List<GitProtocolV2Line> capabilities,
        List<GitProtocolV2Line> arguments,
        Terminal terminal,
        Optional<String> protocolError) {

    public GitProtocolV2Request {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(terminal, "terminal");
        Objects.requireNonNull(protocolError, "protocolError");
        capabilities = List.copyOf(capabilities);
        arguments = List.copyOf(arguments);
    }

    public enum Terminal {
        FLUSH,
        RESPONSE_END,
        ERROR
    }
}
