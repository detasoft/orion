package pro.deta.orion.command;

import java.util.List;
import java.util.Objects;

public record CommandPath(boolean absolute, List<String> segments) {
    public CommandPath {
        Objects.requireNonNull(segments, "segments");
        segments = List.copyOf(segments);
        for (String segment : segments) {
            Objects.requireNonNull(segment, "path segment");
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("path segment must not be empty");
            }
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("path segment must be normalized");
            }
            if (segment.indexOf('/') >= 0) {
                throw new IllegalArgumentException("path segment must not contain '/'");
            }
        }
    }

    public static CommandPath root() {
        return absolute(List.of());
    }

    public static CommandPath absolute(List<String> segments) {
        return new CommandPath(true, segments);
    }

    public static CommandPath relative(List<String> segments) {
        return new CommandPath(false, segments);
    }

    @Override
    public String toString() {
        String joined = String.join("/", segments);
        if (absolute) {
            return "/" + joined;
        }
        return joined;
    }
}
