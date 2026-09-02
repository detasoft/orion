package pro.deta.orion.command;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record CommandAuditDescription(String path, String action, Map<String, String> parameters) {
    public CommandAuditDescription {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(parameters, "parameters");
        parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }
}
