package pro.deta.orion.git.client;

import java.util.List;
import java.util.Objects;

public record GitReceivePackResult(
        GitRemoteAdvertisement advertisement,
        String unpackStatus,
        List<RefStatus> refs) {
    public GitReceivePackResult {
        Objects.requireNonNull(advertisement, "advertisement");
        Objects.requireNonNull(unpackStatus, "unpackStatus");
        refs = List.copyOf(Objects.requireNonNull(refs, "refs"));
    }

    public boolean accepted() {
        if (!"ok".equals(unpackStatus)) {
            return false;
        }
        for (RefStatus ref : refs) {
            if (!ref.accepted()) {
                return false;
            }
        }
        return true;
    }

    public record RefStatus(
            String refName,
            boolean accepted,
            String message) {
        public RefStatus {
            GitClientValidation.requireRefName(refName, "refName");
            Objects.requireNonNull(message, "message");
        }
    }
}
