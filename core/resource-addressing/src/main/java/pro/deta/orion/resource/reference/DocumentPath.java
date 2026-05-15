package pro.deta.orion.resource.reference;

import java.util.Arrays;
import java.util.List;

record DocumentPath(List<String> segments) {
    public DocumentPath {
        if (segments == null || segments.isEmpty()) {
            throw new IllegalArgumentException("Document path must not be empty");
        }
        for (String segment : segments) {
            if (segment == null || segment.isEmpty()) {
                throw new IllegalArgumentException("Document path segment must not be empty");
            }
        }
        segments = List.copyOf(segments);
    }

    public static DocumentPath parse(String raw) {
        if (raw == null || !raw.startsWith("/")) {
            throw new IllegalArgumentException("Document path must start with /");
        }
        return new DocumentPath(Arrays.stream(raw.substring(1).split("/"))
                .filter(segment -> !segment.isEmpty())
                .toList());
    }

    @Override
    public String toString() {
        return "/" + String.join("/", segments);
    }
}
