package pro.deta.orion.git.common;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public record GitReceiveRequest(
        int timeoutSeconds,
        Consumer<List<GitRefUpdate>> afterReceive) {

    public GitReceiveRequest {
        if (timeoutSeconds < 0) {
            throw new IllegalArgumentException("Timeout must not be negative");
        }
        afterReceive = Objects.requireNonNullElseGet(afterReceive, () -> ignored -> {
        });
    }
}
