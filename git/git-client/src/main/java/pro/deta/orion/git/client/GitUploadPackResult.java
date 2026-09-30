package pro.deta.orion.git.client;

import java.util.Objects;

public record GitUploadPackResult<T>(
        GitRemoteAdvertisement advertisement,
        long packBytes,
        T pack) {
    public GitUploadPackResult {
        Objects.requireNonNull(advertisement, "advertisement");
        if (packBytes < 0) {
            throw new IllegalArgumentException("packBytes must not be negative");
        }
    }
}
