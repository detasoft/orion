package pro.deta.orion.git.client.repository;

import java.util.Objects;

public record GitPackId(String value) {
    public GitPackId {
        if (Objects.requireNonNull(value, "value").isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }
}
