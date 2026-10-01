package pro.deta.orion.agent.protocol;

import java.util.Objects;
import java.util.Optional;

public record SessionDescriptor(
        SessionId sessionId,
        AgentMessage.SessionState state,
        Optional<EventId> firstAvailableEventId,
        Optional<EventId> lastAvailableEventId,
        String detail
) {
    public SessionDescriptor {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(state, "state");
        firstAvailableEventId = ProtocolValidation.optional(firstAvailableEventId, "firstAvailableEventId");
        lastAvailableEventId = ProtocolValidation.optional(lastAvailableEventId, "lastAvailableEventId");
        detail = Objects.requireNonNull(detail, "detail");
        validateRange(firstAvailableEventId, lastAvailableEventId);
    }

    static void validateRange(Optional<EventId> first, Optional<EventId> last) {
        if (first.isPresent() != last.isPresent()) {
            throw new IllegalArgumentException(
                    "first and last available event IDs must both be present or absent");
        }
        if (first.isPresent() && first.orElseThrow().compareTo(last.orElseThrow()) > 0) {
            throw new IllegalArgumentException("first available event ID must not be after the last");
        }
    }
}
