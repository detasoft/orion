package pro.deta.orion.agent.protocol;

import java.util.Objects;
import java.util.UUID;

public record AgentLaunchId(UUID value) {
    public AgentLaunchId {
        Objects.requireNonNull(value, "value");
    }
}
