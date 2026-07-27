package pro.deta.orion.git.parser.wire.protocolv2.response;

import java.util.List;
import java.util.Objects;

public record GitFetchAcknowledgments(
        List<String> objectIds,
        boolean nak,
        boolean ready) {

    public GitFetchAcknowledgments {
        Objects.requireNonNull(objectIds, "objectIds");
        objectIds = List.copyOf(objectIds);
        if (nak && !objectIds.isEmpty()) {
            throw new IllegalArgumentException("Fetch acknowledgments cannot contain both ACK and NAK");
        }
    }
}
