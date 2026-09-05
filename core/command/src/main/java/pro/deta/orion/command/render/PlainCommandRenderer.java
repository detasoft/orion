package pro.deta.orion.command.render;

import pro.deta.orion.command.CommandFailureCode;
import pro.deta.orion.command.CommandColumn;
import pro.deta.orion.command.CommandResult;
import pro.deta.orion.command.CommandValue;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PlainCommandRenderer {
    private final TerseCommandRenderer terse = new TerseCommandRenderer();
    private final JsonCommandRenderer json = new JsonCommandRenderer();

    public RenderedCommand render(CommandResult result) {
        Objects.requireNonNull(result, "result");
        if (result instanceof CommandResult.Message message) {
            return success(withNewline(message.value()));
        }
        if (result instanceof CommandResult.Rows rows) {
            if (rows.format() == pro.deta.orion.command.RowOutputFormat.TERSE) {
                return terse.render(rows);
            }
            if (rows.format() == pro.deta.orion.command.RowOutputFormat.JSON) {
                return json.render(rows);
            }
            return success(renderRows(rows));
        }
        if (result instanceof CommandResult.ObjectValue objectValue) {
            return success(renderObject(objectValue));
        }
        if (result instanceof CommandResult.Exit exit) {
            return new RenderedCommand("", withNewline(exit.message()), exit.exitCode());
        }
        if (result instanceof CommandResult.Failure failure) {
            return renderFailure(failure);
        }
        return renderFailure(new CommandResult.Failure(
                CommandFailureCode.UNSUPPORTED_RESULT,
                "Result type is not supported by the plain renderer",
                List.of()));
    }

    private static RenderedCommand success(String stdout) {
        return new RenderedCommand(stdout, "", 0);
    }

    private static String renderRows(CommandResult.Rows rows) {
        StringBuilder output = new StringBuilder();
        appendColumns(output, rows.columns());
        for (List<CommandValue> row : rows.values()) {
            appendRow(output, row);
        }
        RowPaginationRenderer.appendWhenRelevant(output, rows);
        return output.toString();
    }

    private static void appendColumns(StringBuilder output, List<CommandColumn> columns) {
        for (int index = 0; index < columns.size(); index++) {
            if (index > 0) {
                output.append('\t');
            }
            output.append(StructuredValueEscaper.escape(columns.get(index).name()));
        }
        output.append('\n');
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

    private static String renderObject(CommandResult.ObjectValue objectValue) {
        StringBuilder output = new StringBuilder();
        for (Map.Entry<String, CommandValue> field : objectValue.fields().entrySet()) {
            output.append(StructuredValueEscaper.escape(field.getKey()))
                    .append('=')
                    .append(StructuredValueEscaper.escape(field.getValue().asText()))
                    .append('\n');
        }
        return output.toString();
    }

    private static RenderedCommand renderFailure(CommandResult.Failure failure) {
        StringBuilder error = new StringBuilder()
                .append(failure.code().name())
                .append(": ")
                .append(failure.message());
        if (!failure.candidates().isEmpty()) {
            error.append(" [")
                    .append(String.join(", ", failure.candidates()))
                    .append(']');
        }
        error.append('\n');
        return new RenderedCommand("", error.toString(), exitCode(failure.code()));
    }

    private static int exitCode(CommandFailureCode code) {
        return switch (code) {
            case HANDLER_FAILED, SERVICE_UNAVAILABLE -> 1;
            case INVALID_SYNTAX, INVALID_ARGUMENTS -> 2;
            case MISSING_RESOURCE -> 3;
            case AMBIGUOUS_RESOURCE -> 4;
            case ACCESS_DENIED -> 10;
            case CANCELLED -> 125;
            case UNSUPPORTED_RESULT -> 126;
            case UNKNOWN_COMMAND, UNKNOWN_PATH -> 127;
        };
    }

    private static String withNewline(String value) {
        if (value.isEmpty()) {
            return "";
        }
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '\n') {
            end--;
        }
        return value.substring(0, end) + '\n';
    }
}
