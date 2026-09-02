package pro.deta.orion.command;

import pro.deta.orion.auth.SecurityContext;

import java.util.Map;
import java.util.Objects;

public record CommandContext(
        SecurityContext securityContext,
        String requestId,
        String sessionId,
        String sourceAddress,
        CommandPath currentPath,
        CommandPresentation presentation,
        CommandCancellation cancellation,
        Map<String, String> auditMetadata) {
    public CommandContext {
        Objects.requireNonNull(securityContext, "securityContext");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(sourceAddress, "sourceAddress");
        Objects.requireNonNull(currentPath, "currentPath");
        Objects.requireNonNull(presentation, "presentation");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(auditMetadata, "auditMetadata");
        auditMetadata = Map.copyOf(auditMetadata);
    }
}
