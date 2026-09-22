package pro.deta.orion.decision;

import pro.deta.orion.schema.orion.ConfigurationScope;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable description of a pending decision for HTTP and SSH administration.
 * An empty scope identifies a system request; otherwise it belongs to an organization, team, or repository.
 * Actions map identifiers to display labels in presentation order. The producer supplies safe display text;
 * authorization and the waiting operation belong to the registry, not this description.
 */
public record DecisionRequest(
        UUID id,
        Instant createdAt,
        Optional<ConfigurationScope> scope,
        String title,
        String description,
        Map<String, String> actions) {
    public DecisionRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(actions, "actions");
        if (title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        actions = Collections.unmodifiableMap(new LinkedHashMap<>(actions));
        if (actions.isEmpty()) {
            throw new IllegalArgumentException("actions must not be empty");
        }
        for (Map.Entry<String, String> action : actions.entrySet()) {
            Objects.requireNonNull(action.getKey(), "action identifier");
            Objects.requireNonNull(action.getValue(), "action label");
            if (action.getKey().isBlank() || action.getValue().isBlank()) {
                throw new IllegalArgumentException("action identifier and label must not be blank");
            }
        }
    }
}
