package pro.deta.orion.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public sealed interface CommandResult {
    record Message(String value) implements CommandResult {
        public Message {
            Objects.requireNonNull(value, "value");
        }
    }

    record Rows(List<String> columns, List<List<String>> values) implements CommandResult {
        public Rows {
            Objects.requireNonNull(columns, "columns");
            Objects.requireNonNull(values, "values");
            columns = List.copyOf(columns);
            List<List<String>> copiedValues = new ArrayList<>(values.size());
            for (List<String> row : values) {
                copiedValues.add(List.copyOf(row));
            }
            values = List.copyOf(copiedValues);
        }
    }

    record ObjectValue(Map<String, String> fields) implements CommandResult {
        public ObjectValue {
            Objects.requireNonNull(fields, "fields");
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
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
