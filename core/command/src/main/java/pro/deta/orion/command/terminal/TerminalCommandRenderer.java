package pro.deta.orion.command.terminal;

import pro.deta.orion.command.CommandResult;
import pro.deta.orion.command.render.PlainCommandRenderer;
import pro.deta.orion.command.render.RenderedCommand;
import pro.deta.orion.command.render.StructuredValueEscaper;

import java.util.ArrayList;
import java.util.List;

public final class TerminalCommandRenderer {
    private final PlainCommandRenderer plain = new PlainCommandRenderer();

    public RenderedCommand render(CommandResult result, int width) {
        if (!(result instanceof CommandResult.Rows rows) || !rectangular(rows) || !fits(rows, width)) {
            return plain.render(result);
        }
        List<List<String>> allRows = escapedRows(rows);
        int[] widths = widths(allRows, rows.columns().size());
        StringBuilder output = new StringBuilder();
        for (List<String> row : allRows) {
            for (int column = 0; column < row.size(); column++) {
                String value = row.get(column);
                output.append(value);
                if (column < row.size() - 1) {
                    output.append(" ".repeat(widths[column] + 2 - value.length()));
                }
            }
            output.append('\n');
        }
        return new RenderedCommand(output.toString(), "", 0);
    }

    private static boolean rectangular(CommandResult.Rows rows) {
        int columns = rows.columns().size();
        for (List<String> row : rows.values()) {
            if (row.size() != columns) {
                return false;
            }
        }
        return true;
    }

    private static boolean fits(CommandResult.Rows rows, int terminalWidth) {
        if (terminalWidth <= 0 || rows.columns().isEmpty()) {
            return false;
        }
        List<List<String>> allRows = escapedRows(rows);
        int[] widths = widths(allRows, rows.columns().size());
        int required = Math.max(0, widths.length - 1) * 2;
        for (int width : widths) {
            required += width;
        }
        return required <= terminalWidth;
    }

    private static int[] widths(List<List<String>> rows, int columns) {
        int[] widths = new int[columns];
        for (List<String> row : rows) {
            for (int column = 0; column < row.size(); column++) {
                widths[column] = Math.max(widths[column], row.get(column).length());
            }
        }
        return widths;
    }

    private static List<List<String>> escapedRows(CommandResult.Rows rows) {
        List<List<String>> escaped = new ArrayList<>();
        escaped.add(escapeRow(rows.columns()));
        for (List<String> row : rows.values()) {
            escaped.add(escapeRow(row));
        }
        return escaped;
    }

    private static List<String> escapeRow(List<String> row) {
        List<String> escaped = new ArrayList<>(row.size());
        for (String value : row) {
            escaped.add(StructuredValueEscaper.escape(value));
        }
        return escaped;
    }
}
