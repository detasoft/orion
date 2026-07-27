package pro.deta.orion.git.parser.wire.protocolv2.response;

import java.util.Objects;

public record GitLsRefAttribute(
        String name,
        String value,
        String rawToken) {

    public GitLsRefAttribute {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(rawToken, "rawToken");
    }
}
