package pro.deta.orion.command.render;

import org.junit.jupiter.api.Test;
import pro.deta.orion.command.CommandColumn;
import pro.deta.orion.command.CommandResult;
import pro.deta.orion.command.CommandValue;
import pro.deta.orion.command.RowOutputFormat;
import pro.deta.orion.command.RowPage;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

class JsonCommandRendererTest {
    private final JsonCommandRenderer renderer = new JsonCommandRenderer();

    @Test
    void rendersStableEnvelopeWithDeclaredOrderAndExactTypes() {
        CommandResult.Rows rows = new CommandResult.Rows(
                List.of(
                        CommandColumn.text("id"),
                        CommandColumn.number("ratio"),
                        CommandColumn.bool("active"),
                        CommandColumn.text("owner")),
                List.of(List.of(
                        CommandValue.text("demo"),
                        CommandValue.number(new BigDecimal("3.140")),
                        CommandValue.bool(true),
                        CommandValue.nullValue())),
                RowOutputFormat.JSON,
                Optional.of(new RowPage(1, 100, 1, OptionalInt.empty(), false)));

        assertThat(renderer.render(rows)).isEqualTo(new RenderedCommand(
                "{\"columns\":[\"id\",\"ratio\",\"active\",\"owner\"],"
                        + "\"rows\":[{\"id\":\"demo\",\"ratio\":3.140,\"active\":true,\"owner\":null}],"
                        + "\"page\":{\"number\":1,\"size\":100,\"matched\":1,\"next\":null}}\n",
                "",
                0));
    }

    @Test
    void escapesJsonAndTerminalControlsButPreservesUnicode() {
        String hostile = "quote\" slash\\ cr\r lf\n tab\t esc\u001b nul\u0000 c1\u0085 snowman ☃";
        CommandResult.Rows rows = new CommandResult.Rows(
                List.of(CommandColumn.text("value")),
                List.of(List.of(CommandValue.text(hostile))),
                RowOutputFormat.JSON,
                Optional.of(new RowPage(1, 1, 1, OptionalInt.empty(), true)));

        String stdout = renderer.render(rows).stdout();

        assertThat(stdout).contains(
                "quote\\\" slash\\\\ cr\\r lf\\n tab\\t esc\\u001B nul\\u0000 c1\\u0085 snowman ☃");
        assertThat(stdout).doesNotContain("\u001b", "\u0000", "\u0085");
        assertThat(stdout).endsWith("\n");
    }
}
