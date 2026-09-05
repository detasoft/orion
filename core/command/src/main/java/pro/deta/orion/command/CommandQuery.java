package pro.deta.orion.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record CommandQuery(
        boolean enabled,
        List<String> fields,
        Map<String, List<String>> knownValues) {
    public static final Set<String> NAMED_PARAMETERS = Set.of(
            "columns", "page", "page-size", "format");

    public CommandQuery {
        Objects.requireNonNull(fields, "fields");
        Objects.requireNonNull(knownValues, "knownValues");
        List<String> copiedFields = new ArrayList<>(fields.size());
        Set<String> unique = new HashSet<>();
        for (String field : fields) {
            Objects.requireNonNull(field, "field");
            if (field.isBlank() || !unique.add(field)) {
                throw new IllegalArgumentException("query fields must be non-blank and unique");
            }
            copiedFields.add(field);
        }
        fields = List.copyOf(copiedFields);
        Map<String, List<String>> copiedValues = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : knownValues.entrySet()) {
            if (!unique.contains(entry.getKey())) {
                throw new IllegalArgumentException("known values require a declared query field");
            }
            copiedValues.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        knownValues = Collections.unmodifiableMap(copiedValues);
        if (!enabled && (!fields.isEmpty() || !knownValues.isEmpty())) {
            throw new IllegalArgumentException("disabled query metadata must be empty");
        }
    }

    public static CommandQuery none() {
        return new CommandQuery(false, List.of(), Map.of());
    }

    public static CommandQuery enabled(List<String> fields, Map<String, List<String>> knownValues) {
        return new CommandQuery(true, fields, knownValues);
    }
}
