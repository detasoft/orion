package pro.deta.orion.agent.server.registry;

import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.SessionDescriptor;

import java.util.Objects;
import java.util.Optional;

public record SessionRecord(
        AgentId agentId,
        SessionDescriptor reported,
        Optional<Outcome> outcome) {
    public SessionRecord {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(reported, "reported");
        outcome = Objects.requireNonNull(outcome, "outcome");
    }

    public SessionDescriptor descriptor() {
        if (outcome.isEmpty()) {
            return reported;
        }
        Outcome authoritative = outcome.orElseThrow();
        return new SessionDescriptor(
                reported.sessionId(),
                authoritative.state(),
                reported.firstAvailableEventId(),
                reported.lastAvailableEventId(),
                authoritative.detail());
    }

    public record Outcome(AgentMessage.SessionState state, String detail) {
        public Outcome {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(detail, "detail");
            if (state != AgentMessage.SessionState.EXITED && state != AgentMessage.SessionState.FAILED) {
                throw new IllegalArgumentException("authoritative process outcome must be terminal");
            }
        }
    }
}
