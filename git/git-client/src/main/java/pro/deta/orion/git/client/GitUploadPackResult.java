package pro.deta.orion.git.client;

import java.util.Objects;

public record GitUploadPackResult(
        GitRemoteAdvertisement advertisement,
        long packBytes) {
    public GitUploadPackResult {
        Objects.requireNonNull(advertisement, "advertisement");
        if (packBytes < 0) {
            throw new IllegalArgumentException("packBytes must not be negative");
        }
    }
}
