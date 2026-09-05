package pro.deta.orion.command;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record CommandCompletion(Map<String, List<String>> namedValues) {
    public CommandCompletion {
        namedValues = immutableValues(namedValues, "namedValues");
    }

    public static CommandCompletion none() {
        return new CommandCompletion(Map.of());
    }

    private static Map<String, List<String>> immutableValues(
            Map<String, List<String>> values,
            String name) {
        Objects.requireNonNull(values, name);
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : values.entrySet()) {
            copy.put(
                    Objects.requireNonNull(entry.getKey(), name + " key"),
                    List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    public record Result(String line, int cursor, List<String> candidates) {
        public Result {
            Objects.requireNonNull(line, "line");
            Objects.requireNonNull(candidates, "candidates");
            if (cursor < 0 || cursor > line.length()) {
                throw new IllegalArgumentException("cursor is outside line");
            }
            candidates = List.copyOf(candidates);
        }
    }
}
