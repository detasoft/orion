package pro.deta.orion.agentd.protocol;

import java.util.Objects;
import java.util.UUID;

public record InstanceId(UUID value) {
    public InstanceId {
        Objects.requireNonNull(value, "instanceId");
    }
}
