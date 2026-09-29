package pro.deta.orion.command.terminal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import pro.deta.orion.command.CommandFailureCode;
import pro.deta.orion.command.CommandColumn;
import pro.deta.orion.command.CommandResult;
import pro.deta.orion.command.CommandValue;
import pro.deta.orion.command.RowOutputFormat;
import pro.deta.orion.command.RowPage;
import pro.deta.orion.command.render.PlainCommandRenderer;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalCommandRendererTest {
    private final TerminalCommandRenderer renderer = new TerminalCommandRenderer();

    @Test
    void rendersRowsForTheAvailableWidth() {
        CommandResult.Rows rows = textRows(
                List.of("NAME", "STATE"),
                List.of(List.of("first", "running"), List.of("second", "completed")));

        assertThat(renderer.render(rows, 24).stdout())
                .isEqualTo("NAME    STATE\nfirst   running\nsecond  completed\n");
        assertThat(renderer.render(rows, 9).stdout())
                .isEqualTo("NAME\tSTATE\nfirst\trunning\nsecond\tcompleted\n");
    }

    @ParameterizedTest
    @CsvSource({"界界, 4", "é, 1", "👩‍💻, 2", "🇳🇱, 2", "👩‍💻🇳🇱, 4"})
    void alignsUnicodeRowsAndUsesTheExactCellWidthBoundary(String value, int cells) {
        CommandResult.Rows rows = textRows(
                List.of("A", "B"), List.of(List.of(value, "z"), List.of("x", "z")));

        assertThat(renderer.render(rows, cells + 3).stdout()).isEqualTo(
                "A" + " ".repeat(cells + 1) + "B\n"
                        + value + "  z\nx" + " ".repeat(cells + 1) + "z\n");
        assertThat(renderer.render(rows, cells + 2).stdout()).isEqualTo(
                "A\tB\n" + value + "\tz\nx\tz\n");

        PlainCommandRenderer plain = new PlainCommandRenderer();
        for (RowOutputFormat format : List.of(
                RowOutputFormat.PLAIN, RowOutputFormat.TERSE, RowOutputFormat.JSON)) {
            CommandResult.Rows automationRows = new CommandResult.Rows(
                    rows.columns(), rows.values(), format, Optional.empty());
            assertThat(renderer.render(automationRows, 80)).isEqualTo(plain.render(automationRows));
        }
    }

    @Test
    void measuresEscapedUnicodeTextRatherThanTheOriginalValue() {
        CommandResult.Rows rows = textRows(List.of("A", "B"), List.of(List.of("界\n界", "z")));

        assertThat(renderer.render(rows, 9).stdout()).isEqualTo("A       B\n界\\n界  z\n");
        assertThat(renderer.render(rows, 8).stdout()).isEqualTo("A\tB\n界\\n界\tz\n");
    }

    @Test
    void retainsStablePlainExitAndFailureBehavior() {
        assertThat(renderer.render(new CommandResult.Exit(7, "stopped"), 80).exitCode()).isEqualTo(7);
        assertThat(renderer.render(
                new CommandResult.Failure(CommandFailureCode.ACCESS_DENIED, "Access denied", List.of()),
                80).stderr())
                .isEqualTo("ACCESS_DENIED: Access denied\n");
    }

    @Test
    void measuresAndRendersEscapedStructuredValues() {
        CommandResult.Rows rows = textRows(
                List.of("NAME", "STATE"),
                List.of(List.of("a\nb", "x\u001b\\y")));

        assertThat(renderer.render(rows, 16).stdout())
                .isEqualTo("NAME  STATE\na\\nb  x\\u001B\\\\y\n");
        assertThat(renderer.render(rows, 15).stdout())
                .isEqualTo("NAME\tSTATE\na\\nb\tx\\u001B\\\\y\n");
    }

    @Test
    void usesTheSameExplicitAutomationFormatsAsThePlainFrontend() {
        PlainCommandRenderer plain = new PlainCommandRenderer();
        for (RowOutputFormat format : List.of(
                RowOutputFormat.PLAIN, RowOutputFormat.TERSE, RowOutputFormat.JSON)) {
            CommandResult.Rows rows = formattedRows(format);
            assertThat(renderer.render(rows, 80)).isEqualTo(plain.render(rows));
        }
    }

    @Test
    void appendsPaginationToTablesAndPlainFallbacks() {
        CommandResult.Rows rows = formattedRows(RowOutputFormat.TABLE);

        assertThat(renderer.render(rows, 80).stdout()).isEqualTo(
                "id   count\none  2\n# page=1 page-size=1 matched=2 next-page=2\n");
        assertThat(renderer.render(rows, 4).stdout()).isEqualTo(
                "id\tcount\none\t2\n# page=1 page-size=1 matched=2 next-page=2\n");
    }

    private static CommandResult.Rows formattedRows(RowOutputFormat format) {
        return new CommandResult.Rows(
                List.of(CommandColumn.text("id"), CommandColumn.number("count")),
                List.of(List.of(CommandValue.text("one"), CommandValue.number(2))),
                format,
                Optional.of(new RowPage(1, 1, 2, OptionalInt.of(2), false)));
    }

    private static CommandResult.Rows textRows(List<String> columns, List<List<String>> values) {
        List<pro.deta.orion.command.CommandColumn> typedColumns = new java.util.ArrayList<>();
        for (String column : columns) {
            typedColumns.add(pro.deta.orion.command.CommandColumn.text(column));
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
}
