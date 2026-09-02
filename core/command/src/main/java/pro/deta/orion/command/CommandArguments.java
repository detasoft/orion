package pro.deta.orion.command;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record CommandArguments(
        List<String> positional,
        Map<String, String> named,
        List<WherePredicate> predicates) {
    public CommandArguments {
        Objects.requireNonNull(positional, "positional");
        Objects.requireNonNull(named, "named");
        Objects.requireNonNull(predicates, "predicates");
        positional = List.copyOf(positional);
        named = Collections.unmodifiableMap(new LinkedHashMap<>(named));
        predicates = List.copyOf(predicates);
    }
}
