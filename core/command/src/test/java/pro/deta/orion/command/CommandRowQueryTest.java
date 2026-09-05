package pro.deta.orion.command;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CommandRowQueryTest {
    private final CommandRowQuery query = new CommandRowQuery();
    private final CommandQuery metadata = CommandQuery.enabled(
            List.of("id", "state", "owner", "attempts", "active", "repository"),
            Map.of("state", List.of("RUNNING", "COMPLETED")));

    @Test
    void filtersBeforeProjectionAndPaginationWhilePreservingOrder() {
        CommandArguments arguments = arguments(
                Map.of("columns", "id,attempts", "page", "2", "page-size", "1", "format", "json"),
                List.of(
                        predicate("state", WherePredicate.Operator.EQUALS, "RUNNING"),
                        predicate("owner", WherePredicate.Operator.NOT_EQUALS, "bot")));

        CommandResult result = query.apply(rows(), arguments, metadata, CommandPresentation.plain());

        assertThat(result).isEqualTo(new CommandResult.Rows(
                List.of(CommandColumn.text("id"), CommandColumn.number("attempts")),
                List.of(List.of(CommandValue.text("three"), CommandValue.number(3))),
                RowOutputFormat.JSON,
                java.util.Optional.of(new RowPage(
                        2, 1, 2, java.util.OptionalInt.empty(), true))));
    }

    @Test
    void comparesTypedValuesIncludingNullAndEnums() {
        assertIds(arguments(Map.of(), List.of(predicate("repository", WherePredicate.Operator.EQUALS, "null"))),
                "one", "three");
        assertIds(arguments(Map.of(), List.of(predicate("repository", WherePredicate.Operator.NOT_EQUALS, "null"))),
                "two");
        assertIds(arguments(Map.of(), List.of(predicate("attempts", WherePredicate.Operator.EQUALS, "2.0"))),
                "two");
        assertIds(arguments(Map.of(), List.of(predicate("active", WherePredicate.Operator.EQUALS, "true"))),
                "one", "three");
        assertIds(arguments(Map.of(), List.of(predicate("state", WherePredicate.Operator.EQUALS, "COMPLETED"))),
                "two");
    }

    @Test
    void rejectsInvalidFieldsValuesColumnsPagesAndNoPtyTables() {
        assertInvalid(arguments(Map.of(), List.of(predicate("secret", WherePredicate.Operator.EQUALS, "value"))),
                "field");
        assertInvalid(arguments(Map.of(), List.of(predicate("attempts", WherePredicate.Operator.EQUALS, "many"))),
                "number");
        assertInvalid(arguments(Map.of(), List.of(predicate("active", WherePredicate.Operator.EQUALS, "yes"))),
                "boolean");
        assertInvalid(arguments(Map.of(), List.of(predicate("state", WherePredicate.Operator.EQUALS, "UNKNOWN"))),
                "state");
        assertInvalid(arguments(Map.of("columns", "id,id"), List.of()), "column");
        assertInvalid(arguments(Map.of("columns", "id,secret"), List.of()), "column");
        assertInvalid(arguments(Map.of("page", "0"), List.of()), "page");
        assertInvalid(arguments(Map.of("page-size", "501"), List.of()), "page-size");
        assertInvalid(arguments(Map.of("format", "table"), List.of()), "table");
    }

    @Test
    void returnsStableEmptyMetadataForPagesBeyondTheEnd() {
        CommandResult result = query.apply(
                rows(),
                arguments(Map.of("page", Integer.toString(Integer.MAX_VALUE)), List.of()),
                metadata,
                CommandPresentation.plain());

        CommandResult.Rows rows = (CommandResult.Rows) result;
        assertThat(rows.values()).isEmpty();
        assertThat(rows.page()).contains(new RowPage(
                Integer.MAX_VALUE, 100, 3, java.util.OptionalInt.empty(), true));
    }

    @Test
    void validatesPredicateLiteralsEvenWhenTheHandlerReturnsNoRows() {
        CommandResult.Rows empty = CommandResult.Rows.unqueried(rows().columns(), List.of());

        CommandResult result = query.apply(
                empty,
                arguments(
                        Map.of(),
                        List.of(predicate("attempts", WherePredicate.Operator.EQUALS, "many"))),
                metadata,
                CommandPresentation.plain());

        assertThat(result).isInstanceOf(CommandResult.Failure.class);
        assertThat(((CommandResult.Failure) result).message()).containsIgnoringCase("number");
    }

    @Test
    void requiresQueryMetadataToMatchTheCompleteResultSchemaInOrder() {
        CommandResult.Rows extra = CommandResult.Rows.unqueried(
                List.of(CommandColumn.text("id"), CommandColumn.text("secret")),
                List.of(List.of(CommandValue.text("one"), CommandValue.text("hidden"))));
        assertMetadataFailure(extra, CommandQuery.enabled(List.of("id"), Map.of()));

        CommandResult.Rows reordered = CommandResult.Rows.unqueried(
                List.of(CommandColumn.text("state"), CommandColumn.text("id")),
                List.of(List.of(CommandValue.text("RUNNING"), CommandValue.text("one"))));
        assertMetadataFailure(
                reordered,
                CommandQuery.enabled(List.of("id", "state"), Map.of()));
    }

    private void assertIds(CommandArguments arguments, String... ids) {
        CommandResult.Rows result = (CommandResult.Rows) query.apply(
                rows(), arguments, metadata, CommandPresentation.plain());

        assertThat(result.values())
                .extracting(row -> ((CommandValue.Text) row.getFirst()).value())
                .containsExactly(ids);
    }

    private void assertInvalid(CommandArguments arguments, String message) {
        CommandResult result = query.apply(rows(), arguments, metadata, CommandPresentation.plain());

        assertThat(result).isInstanceOf(CommandResult.Failure.class);
        CommandResult.Failure failure = (CommandResult.Failure) result;
        assertThat(failure.code()).isEqualTo(CommandFailureCode.INVALID_ARGUMENTS);
        assertThat(failure.message()).containsIgnoringCase(message);
    }

    private void assertMetadataFailure(CommandResult.Rows rows, CommandQuery queryMetadata) {
        CommandResult result = query.apply(
                rows,
                arguments(Map.of(), List.of()),
                queryMetadata,
                CommandPresentation.plain());

        assertThat(result).isEqualTo(new CommandResult.Failure(
                CommandFailureCode.HANDLER_FAILED,
                "Command result does not match query metadata",
                List.of()));
    }

    private static CommandArguments arguments(
            Map<String, String> named,
            List<WherePredicate> predicates) {
        return new CommandArguments(List.of(), named, predicates);
    }

    private static WherePredicate predicate(
            String field,
            WherePredicate.Operator operator,
            String value) {
        return new WherePredicate(field, operator, value);
    }

    private static CommandResult.Rows rows() {
        return CommandResult.Rows.unqueried(
                List.of(
                        CommandColumn.text("id"),
                        CommandColumn.text("state"),
                        CommandColumn.text("owner"),
                        CommandColumn.number("attempts"),
                        CommandColumn.bool("active"),
                        CommandColumn.text("repository")),
                List.of(
                        row("one", "RUNNING", "ops", 1, true, null),
                        row("two", "COMPLETED", "bot", 2, false, "demo"),
                        row("three", "RUNNING", "ops", 3, true, null)));
    }

    private static List<CommandValue> row(Object... values) {
        List<CommandValue> row = new ArrayList<>(values.length);
        for (Object value : values) {
            if (value == null) {
                row.add(CommandValue.nullValue());
            } else if (value instanceof String string) {
                row.add(CommandValue.text(string));
            } else if (value instanceof Integer integer) {
                row.add(CommandValue.number(integer));
            } else if (value instanceof Boolean booleanValue) {
                row.add(CommandValue.bool(booleanValue));
            }
        }
        return List.copyOf(row);
    }
}
