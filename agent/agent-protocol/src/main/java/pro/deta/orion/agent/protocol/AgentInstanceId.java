package pro.deta.orion.agent.protocol;

import java.util.Objects;
import java.util.UUID;

public record AgentInstanceId(UUID value) {
    public AgentInstanceId {
        Objects.requireNonNull(value, "value");
    }
}
