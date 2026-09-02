package pro.deta.orion.command.audit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record CommandAuditRecord(
        String userId,
        String requestId,
        String sessionId,
        String sourceAddress,
        String commandPath,
        String action,
        Map<String, String> parameters,
        String resultKind,
        String resultCode,
        long durationNanos,
        Map<String, String> auditMetadata) {
    public CommandAuditRecord {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(sourceAddress, "sourceAddress");
        Objects.requireNonNull(commandPath, "commandPath");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(resultKind, "resultKind");
        Objects.requireNonNull(resultCode, "resultCode");
        Objects.requireNonNull(auditMetadata, "auditMetadata");
        if (durationNanos < 0) {
            throw new IllegalArgumentException("durationNanos must not be negative");
        }
        parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        auditMetadata = Collections.unmodifiableMap(new LinkedHashMap<>(auditMetadata));
    }
}
