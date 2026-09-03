package pro.deta.orion.agentd.journal;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public sealed interface JournalReadIssue permits JournalReadIssue.Io, JournalReadIssue.Layout,
        JournalReadIssue.Decompression, JournalReadIssue.Cbor, JournalReadIssue.Record,
        JournalReadIssue.EventOrder, JournalReadIssue.Limit {
    Optional<Path> segment();

    String detail();

    record Io(Optional<Path> segment, String detail) implements JournalReadIssue {
        public Io {
            validate(segment, detail);
        }
    }

    record Layout(Optional<Path> segment, String detail) implements JournalReadIssue {
        public Layout {
            validate(segment, detail);
        }
    }

    record Decompression(Optional<Path> segment, String detail) implements JournalReadIssue {
        public Decompression {
            validate(segment, detail);
        }
    }

    record Cbor(Optional<Path> segment, String detail) implements JournalReadIssue {
        public Cbor {
            validate(segment, detail);
        }
    }

    record Record(Optional<Path> segment, String detail) implements JournalReadIssue {
        public Record {
            validate(segment, detail);
        }
    }

    record EventOrder(Optional<Path> segment, String detail) implements JournalReadIssue {
        public EventOrder {
            validate(segment, detail);
        }
    }

    record Limit(Optional<Path> segment, String detail) implements JournalReadIssue {
        public Limit {
            validate(segment, detail);
        }
    }

    private static void validate(Optional<Path> segment, String detail) {
        Objects.requireNonNull(segment, "segment");
        Objects.requireNonNull(detail, "detail");
        if (detail.isBlank()) {
            throw new IllegalArgumentException("detail must not be blank");
        }
    }
}
