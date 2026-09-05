package pro.deta.orion.command.render;

import org.junit.jupiter.api.Test;
import pro.deta.orion.command.CommandFailureCode;
import pro.deta.orion.command.CommandColumn;
import pro.deta.orion.command.CommandResult;
import pro.deta.orion.command.CommandValue;
import pro.deta.orion.command.RowOutputFormat;
import pro.deta.orion.command.RowPage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

class PlainCommandRendererTest {
    private final PlainCommandRenderer renderer = new PlainCommandRenderer();

    @Test
    void rendersMessagesWithExactlyOneTrailingNewline() {
        assertThat(renderer.render(new CommandResult.Message("hello\n")))
                .isEqualTo(new RenderedCommand("hello\n", "", 0));
        assertThat(renderer.render(new CommandResult.Message("")))
                .isEqualTo(new RenderedCommand("", "", 0));
    }

    @Test
    void rendersRowsAsStableTabSeparatedPlainText() {
        CommandResult.Rows rows = textRows(
                List.of("name", "state"),
                List.of(List.of("alpha", "ready"), List.of("beta", "stopped")));

        RenderedCommand rendered = renderer.render(rows);

        assertThat(rendered).isEqualTo(new RenderedCommand(
                "name\tstate\nalpha\tready\nbeta\tstopped\n",
                "",
                0));
        assertThat(rendered.stdout()).doesNotContain("\u001B");
    }

    @Test
    void appendsPaginationOnlyWhenItIsRelevant() {
        CommandResult.Rows complete = rows(
                RowOutputFormat.PLAIN,
                new RowPage(1, 100, 1, OptionalInt.empty(), false));
        CommandResult.Rows truncated = rows(
                RowOutputFormat.PLAIN,
                new RowPage(1, 1, 2, OptionalInt.of(2), false));

        assertThat(renderer.render(complete).stdout()).isEqualTo("id\none\n");
        assertThat(renderer.render(truncated).stdout()).isEqualTo(
                "id\none\n# page=1 page-size=1 matched=2 next-page=2\n");
    }

    private static CommandResult.Rows rows(RowOutputFormat format, RowPage page) {
        return new CommandResult.Rows(
                List.of(CommandColumn.text("id")),
                List.of(List.of(CommandValue.text("one"))),
                format,
                Optional.of(page));
    }

    @Test
    void rendersObjectsInInsertionOrder() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("second", "2");
        fields.put("first", "1");

        assertThat(renderer.render(textObject(fields)))
                .isEqualTo(new RenderedCommand("second=2\nfirst=1\n", "", 0));
    }

    @Test
    void escapesStructuredKeysAndValuesWithoutChangingMessageSemantics() {
        String hostile = "line\\break\r\n\t\u001b\u0000\u0085";
        CommandResult.Rows rows = textRows(
                List.of(hostile),
                List.of(List.of(hostile)));
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(hostile, hostile);

        assertThat(renderer.render(rows).stdout()).isEqualTo(
                "line\\\\break\\r\\n\\t\\u001B\\u0000\\u0085\n"
                        + "line\\\\break\\r\\n\\t\\u001B\\u0000\\u0085\n");
        assertThat(renderer.render(textObject(fields)).stdout()).isEqualTo(
                "line\\\\break\\r\\n\\t\\u001B\\u0000\\u0085="
                        + "line\\\\break\\r\\n\\t\\u001B\\u0000\\u0085\n");
        assertThat(renderer.render(new CommandResult.Message("first\nsecond")).stdout())
                .isEqualTo("first\nsecond\n");
    }

    @Test
    void explicitExitPreservesCodeAndWritesMessageToStderr() {
        assertThat(renderer.render(new CommandResult.Exit(7, "stopped")))
                .isEqualTo(new RenderedCommand("", "stopped\n", 7));
        assertThat(renderer.render(new CommandResult.Exit(0, "")))
                .isEqualTo(new RenderedCommand("", "", 0));
    }

    @Test
    void failuresUseStablePrefixesAndExitCodes() {
        Map<CommandFailureCode, Integer> expected = Map.ofEntries(
                Map.entry(CommandFailureCode.HANDLER_FAILED, 1),
                Map.entry(CommandFailureCode.INVALID_SYNTAX, 2),
                Map.entry(CommandFailureCode.INVALID_ARGUMENTS, 2),
                Map.entry(CommandFailureCode.MISSING_RESOURCE, 3),
                Map.entry(CommandFailureCode.AMBIGUOUS_RESOURCE, 4),
                Map.entry(CommandFailureCode.ACCESS_DENIED, 10),
                Map.entry(CommandFailureCode.CANCELLED, 125),
                Map.entry(CommandFailureCode.UNSUPPORTED_RESULT, 126),
                Map.entry(CommandFailureCode.UNKNOWN_COMMAND, 127),
                Map.entry(CommandFailureCode.UNKNOWN_PATH, 127));

        for (Map.Entry<CommandFailureCode, Integer> entry : expected.entrySet()) {
            CommandResult.Failure failure = new CommandResult.Failure(
                    entry.getKey(),
                    "detail",
                    List.of());
            assertThat(renderer.render(failure)).isEqualTo(new RenderedCommand(
                    "",
                    entry.getKey().name() + ": detail\n",
                    entry.getValue()));
        }
    }

    @Test
    void includesSafeAmbiguityCandidates() {
        CommandResult.Failure failure = new CommandResult.Failure(
                CommandFailureCode.AMBIGUOUS_RESOURCE,
                "choose a longer prefix",
                List.of("abc1", "abc2"));

        assertThat(renderer.render(failure).stderr())
                .isEqualTo("AMBIGUOUS_RESOURCE: choose a longer prefix [abc1, abc2]\n");
    }

    @Test
    void streamAndAttachmentReservationsAreStableUnsupportedResults() {
        CommandResult.Stream stream = new CommandResult.Stream(new CommandResult.StreamHandle() {});
        CommandResult.Attachment attachment =
                new CommandResult.Attachment(new CommandResult.AttachmentHandle() {});

        RenderedCommand unsupported = new RenderedCommand(
                "",
                "UNSUPPORTED_RESULT: Result type is not supported by the plain renderer\n",
                126);
        assertThat(renderer.render(stream)).isEqualTo(unsupported);
        assertThat(renderer.render(attachment)).isEqualTo(unsupported);
    }

    private static CommandResult.Rows textRows(List<String> columns, List<List<String>> values) {
        List<CommandColumn> typedColumns = new java.util.ArrayList<>();
        for (String column : columns) {
            typedColumns.add(CommandColumn.text(column));
        }
        List<List<CommandValue>> typedValues = new java.util.ArrayList<>();
        for (List<String> row : values) {
            List<CommandValue> typedRow = new java.util.ArrayList<>();
            for (String value : row) {
                typedRow.add(CommandValue.text(value));
            }
            typedValues.add(typedRow);
        }
        return CommandResult.Rows.unqueried(typedColumns, typedValues);
    }

    private static CommandResult.ObjectValue textObject(Map<String, String> fields) {
        Map<String, CommandValue> typed = new LinkedHashMap<>();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            typed.put(field.getKey(), CommandValue.text(field.getValue()));
        }
        return new CommandResult.ObjectValue(typed);
    }
}
