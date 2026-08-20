package pro.deta.orion.git.common;

import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

public record GitUploadRequest(
        int timeoutSeconds,
        Set<String> extraParameters,
        Consumer<GitUploadStats> afterUpload) {

    public GitUploadRequest {
        if (timeoutSeconds < 0) {
            throw new IllegalArgumentException("Timeout must not be negative");
        }
        extraParameters = Set.copyOf(Objects.requireNonNullElse(extraParameters, Set.of()));
        afterUpload = Objects.requireNonNullElseGet(afterUpload, () -> ignored -> {
        });
    }
}
