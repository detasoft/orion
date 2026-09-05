package pro.deta.orion.command.render;

import pro.deta.orion.command.CommandResult;
import pro.deta.orion.command.CommandValue;

import java.util.List;
import java.util.Objects;

public final class TerseCommandRenderer {
    public RenderedCommand render(CommandResult.Rows rows) {
        Objects.requireNonNull(rows, "rows");
        StringBuilder output = new StringBuilder();
        for (List<CommandValue> row : rows.values()) {
            appendRow(output, row);
        }
        RowPaginationRenderer.appendWhenRelevant(output, rows);
        return new RenderedCommand(output.toString(), "", 0);
    }

    private static void appendRow(StringBuilder output, List<CommandValue> row) {
        for (int index = 0; index < row.size(); index++) {
            if (index > 0) {
                output.append('\t');
            }
            output.append(StructuredValueEscaper.escape(row.get(index).asText()));
        }
        output.append('\n');
    }
}
