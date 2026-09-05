package pro.deta.orion.command.render;

import org.junit.jupiter.api.Test;
import pro.deta.orion.command.CommandColumn;
import pro.deta.orion.command.CommandResult;
import pro.deta.orion.command.CommandValue;
import pro.deta.orion.command.RowOutputFormat;
import pro.deta.orion.command.RowPage;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

class TerseCommandRendererTest {
    private final TerseCommandRenderer renderer = new TerseCommandRenderer();

    @Test
    void rendersHeaderlessEscapedTypedRowsAndPagination() {
        CommandResult.Rows rows = new CommandResult.Rows(
                List.of(CommandColumn.text("id"), CommandColumn.number("count")),
                List.of(
                        List.of(CommandValue.text("line\n\t\\\u001b"), CommandValue.number(2)),
                        List.of(CommandValue.nullValue(), CommandValue.number(3))),
                RowOutputFormat.TERSE,
                Optional.of(new RowPage(2, 2, 4, OptionalInt.empty(), true)));

        assertThat(renderer.render(rows)).isEqualTo(new RenderedCommand(
                "line\\n\\t\\\\\\u001B\t2\nnull\t3\n"
                        + "# page=2 page-size=2 matched=4 next-page=null\n",
                "",
                0));
    }
}
