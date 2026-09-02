package pro.deta.orion.command;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ParsedCommand(
        CommandPath path,
        String action,
        List<String> positionalArguments,
        Map<String, String> namedParameters,
        List<WherePredicate> predicates) {
    public ParsedCommand {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(positionalArguments, "positionalArguments");
        Objects.requireNonNull(namedParameters, "namedParameters");
        Objects.requireNonNull(predicates, "predicates");
        positionalArguments = List.copyOf(positionalArguments);
        namedParameters = Collections.unmodifiableMap(new LinkedHashMap<>(namedParameters));
        predicates = List.copyOf(predicates);
    }
}
