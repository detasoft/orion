package pro.deta.orion.agentd.session;

import java.nio.file.Path;
import java.util.Objects;

public record ControlEndpoint(Transport transport, String endpoint, Path address) {
    public ControlEndpoint {
        Objects.requireNonNull(transport, "transport");
        endpoint = requireValue(endpoint, "endpoint");
        Objects.requireNonNull(address, "address");
    }

    public enum Transport {
        UNIX_DOMAIN_SOCKET,
        NAMED_PIPE
    }

    private static String requireValue(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
