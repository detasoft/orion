package pro.deta.orion.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public sealed interface CommandResult {
    record Message(String value) implements CommandResult {
        public Message {
            Objects.requireNonNull(value, "value");
        }
    }

    record Rows(
            List<CommandColumn> columns,
            List<List<CommandValue>> values,
            RowOutputFormat format,
            Optional<RowPage> page) implements CommandResult {
        public Rows {
            Objects.requireNonNull(columns, "columns");
            Objects.requireNonNull(values, "values");
            Objects.requireNonNull(format, "format");
            Objects.requireNonNull(page, "page");
            columns = List.copyOf(columns);
            if (columns.isEmpty()) {
                throw new IllegalArgumentException("rows require at least one column");
            }
            Set<String> names = new HashSet<>();
            for (CommandColumn column : columns) {
                if (!names.add(column.name())) {
                    throw new IllegalArgumentException("column names must be unique: " + column.name());
                }
            }
            List<List<CommandValue>> copiedValues = new ArrayList<>(values.size());
            for (List<CommandValue> row : values) {
                List<CommandValue> copiedRow = List.copyOf(row);
                if (copiedRow.size() != columns.size()) {
                    throw new IllegalArgumentException("row column count does not match declared columns");
                }
                for (int index = 0; index < copiedRow.size(); index++) {
                    if (!columns.get(index).accepts(copiedRow.get(index))) {
                        throw new IllegalArgumentException(
                                "row value does not match column " + columns.get(index).name());
                    }
                }
                copiedValues.add(copiedRow);
            }
            values = List.copyOf(copiedValues);
        }

        public static Rows unqueried(
                List<CommandColumn> columns,
                List<List<CommandValue>> values) {
            return new Rows(columns, values, RowOutputFormat.AUTO, Optional.empty());
        }
    }

    record ObjectValue(Map<String, CommandValue> fields) implements CommandResult {
        public ObjectValue {
            Objects.requireNonNull(fields, "fields");
            Map<String, CommandValue> copiedFields = new LinkedHashMap<>();
            for (Map.Entry<String, CommandValue> field : fields.entrySet()) {
                copiedFields.put(
                        Objects.requireNonNull(field.getKey(), "field name"),
                        Objects.requireNonNull(field.getValue(), "field value"));
            }
            fields = Collections.unmodifiableMap(copiedFields);
        }
    }

    record Stream(StreamHandle handle) implements CommandResult {
        public Stream {
            Objects.requireNonNull(handle, "handle");
        }
    }

    record Attachment(AttachmentHandle handle) implements CommandResult {
        public Attachment {
            Objects.requireNonNull(handle, "handle");
        }
    }

    record Exit(int exitCode, String message) implements CommandResult {
        public Exit {
            Objects.requireNonNull(message, "message");
        }
    }

    record Failure(CommandFailureCode code, String message, List<String> candidates) implements CommandResult {
        public Failure {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(candidates, "candidates");
            candidates = List.copyOf(candidates);
        }
    }

    interface StreamHandle {}

    interface AttachmentHandle {}
}
