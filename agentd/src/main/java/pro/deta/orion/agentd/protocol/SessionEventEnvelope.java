package pro.deta.orion.agentd.protocol;

import java.util.Objects;

public record SessionEventEnvelope(
        SessionTimestamp sourceTimestamp,
        int eventType,
        int payloadSchemaVersion,
        long flags,
        ProtocolBytes payload
) {
    public SessionEventEnvelope {
        Objects.requireNonNull(sourceTimestamp, "sourceTimestamp");
        eventType = ProtocolValidation.unsignedShort(eventType, "eventType");
        payloadSchemaVersion = ProtocolValidation.unsignedShort(payloadSchemaVersion, "payloadSchemaVersion");
        flags = ProtocolValidation.unsignedInt(flags, "flags");
        Objects.requireNonNull(payload, "payload");
    }
}
