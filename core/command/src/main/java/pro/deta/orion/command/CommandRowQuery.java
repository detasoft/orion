package pro.deta.orion.command;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

public final class CommandRowQuery {
    public static final int DEFAULT_PAGE_SIZE = 100;
    public static final int MAXIMUM_PAGE_SIZE = 500;

    public CommandResult apply(
            CommandResult.Rows rows,
            CommandArguments arguments,
            CommandQuery query,
            CommandPresentation presentation) {
        Map<String, Integer> indexes = indexes(rows.columns());
        CommandResult failure = validateMetadata(query, rows.columns());
        if (failure != null) {
            return failure;
        }
        RowOutputFormat format = format(arguments.named().get("format"));
        if (format == null) {
            return invalid("Unknown output format");
        }
        if (format == RowOutputFormat.TABLE
                && (!presentation.interactive() || presentation.terminalColumns() <= 0)) {
            return invalid("Table format requires an interactive terminal");
        }
        Integer page = positiveInteger(arguments.named().getOrDefault("page", "1"));
        if (page == null) {
            return invalid("Page must be a positive integer");
        }
        Integer pageSize = positiveInteger(arguments.named().getOrDefault(
                "page-size", Integer.toString(DEFAULT_PAGE_SIZE)));
        if (pageSize == null || pageSize > MAXIMUM_PAGE_SIZE) {
            return invalid("Page-size must be between 1 and " + MAXIMUM_PAGE_SIZE);
        }
        List<Integer> selected = selectedColumns(arguments.named().get("columns"), query.fields(), indexes);
        if (selected == null) {
            return invalid("Columns must name unique declared fields");
        }
        CommandResult.Failure predicateFailure = validatePredicates(
                rows.columns(), arguments.predicates(), query, indexes);
        if (predicateFailure != null) {
            return predicateFailure;
        }
        List<List<CommandValue>> filtered = new ArrayList<>();
        for (List<CommandValue> row : rows.values()) {
            if (matches(row, rows.columns(), arguments.predicates(), indexes)) {
                filtered.add(row);
            }
        }
        long offset = (long) (page - 1) * pageSize;
        int start = offset >= filtered.size() ? filtered.size() : (int) offset;
        int end = Math.min(filtered.size(), start + pageSize);
        List<CommandColumn> projectedColumns = new ArrayList<>(selected.size());
        for (int index : selected) {
            projectedColumns.add(rows.columns().get(index));
        }
        List<List<CommandValue>> projectedRows = new ArrayList<>(end - start);
        for (int rowIndex = start; rowIndex < end; rowIndex++) {
            List<CommandValue> projected = new ArrayList<>(selected.size());
            for (int columnIndex : selected) {
                projected.add(filtered.get(rowIndex).get(columnIndex));
            }
            projectedRows.add(projected);
        }
        OptionalInt next = end < filtered.size() ? OptionalInt.of(page + 1) : OptionalInt.empty();
        boolean explicit = arguments.named().containsKey("page")
                || arguments.named().containsKey("page-size");
        return new CommandResult.Rows(
                projectedColumns,
                projectedRows,
                format,
                Optional.of(new RowPage(page, pageSize, filtered.size(), next, explicit)));
    }

    private static CommandResult validateMetadata(CommandQuery query, List<CommandColumn> columns) {
        if (query.fields().size() != columns.size()) {
            return metadataFailure();
        }
        for (int index = 0; index < columns.size(); index++) {
            if (!query.fields().get(index).equals(columns.get(index).name())) {
                return metadataFailure();
            }
        }
        return null;
    }

    private static CommandResult.Failure metadataFailure() {
        return new CommandResult.Failure(
                CommandFailureCode.HANDLER_FAILED,
                "Command result does not match query metadata",
                List.of());
    }

    private static List<Integer> selectedColumns(
            String value,
            List<String> fields,
            Map<String, Integer> indexes) {
        if (value == null) {
            List<Integer> all = new ArrayList<>(indexes.size());
            for (int index = 0; index < indexes.size(); index++) {
                all.add(index);
            }
            return all;
        }
        List<Integer> selected = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (String raw : value.split(",", -1)) {
            String name = raw.trim();
            Integer index = indexes.get(name);
            if (name.isEmpty() || !fields.contains(name) || index == null || !unique.add(name)) {
                return null;
            }
            selected.add(index);
        }
        return selected;
    }

    private static CommandResult.Failure validatePredicates(
            List<CommandColumn> columns,
            List<WherePredicate> predicates,
            CommandQuery query,
            Map<String, Integer> indexes) {
        for (WherePredicate predicate : predicates) {
            if (!query.fields().contains(predicate.field())) {
                return invalid("Unknown where field: " + predicate.field());
            }
            int index = indexes.get(predicate.field());
            CommandResult.Failure validation = validateLiteral(
                    columns.get(index), predicate, query.knownValues().get(predicate.field()));
            if (validation != null) {
                return validation;
            }
        }
        return null;
    }

    private static boolean matches(
            List<CommandValue> row,
            List<CommandColumn> columns,
            List<WherePredicate> predicates,
            Map<String, Integer> indexes) {
        for (WherePredicate predicate : predicates) {
            int index = indexes.get(predicate.field());
            boolean equal = equals(row.get(index), columns.get(index), predicate.value());
            boolean matches = predicate.operator() == WherePredicate.Operator.EQUALS ? equal : !equal;
            if (!matches) {
                return false;
            }
        }
        return true;
    }

    private static CommandResult.Failure validateLiteral(
            CommandColumn column,
            WherePredicate predicate,
            List<String> knownValues) {
        String value = predicate.value();
        if (value.equals("null")) {
            return null;
        }
        if (knownValues != null && !knownValues.contains(value)) {
            return invalid("Unknown value for " + predicate.field());
        }
        return switch (column.type()) {
            case TEXT -> null;
            case NUMBER -> parseNumber(value) == null
                    ? invalid("Where value for " + predicate.field() + " must be a number")
                    : null;
            case BOOLEAN -> !value.equals("true") && !value.equals("false")
                    ? invalid("Where value for " + predicate.field() + " must be a boolean")
                    : null;
        };
    }

    private static boolean equals(CommandValue actual, CommandColumn column, String expected) {
        if (expected.equals("null")) {
            return actual instanceof CommandValue.NullValue;
        }
        if (actual instanceof CommandValue.NullValue) {
            return false;
        }
        return switch (column.type()) {
            case TEXT -> ((CommandValue.Text) actual).value().equals(expected);
            case NUMBER -> ((CommandValue.Numeric) actual).value().compareTo(parseNumber(expected)) == 0;
            case BOOLEAN -> ((CommandValue.BooleanValue) actual).value() == Boolean.parseBoolean(expected);
        };
    }

    private static BigDecimal parseNumber(String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Integer positiveInteger(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static RowOutputFormat format(String value) {
        if (value == null) {
            return RowOutputFormat.AUTO;
        }
        return switch (value) {
            case "plain" -> RowOutputFormat.PLAIN;
            case "terse" -> RowOutputFormat.TERSE;
            case "json" -> RowOutputFormat.JSON;
            case "table" -> RowOutputFormat.TABLE;
            default -> null;
        };
    }

    private static Map<String, Integer> indexes(List<CommandColumn> columns) {
        Map<String, Integer> indexes = new HashMap<>();
        for (int index = 0; index < columns.size(); index++) {
            indexes.put(columns.get(index).name(), index);
        }
        return indexes;
    }

    private static CommandResult.Failure invalid(String message) {
        return new CommandResult.Failure(CommandFailureCode.INVALID_ARGUMENTS, message, List.of());
    }
}
