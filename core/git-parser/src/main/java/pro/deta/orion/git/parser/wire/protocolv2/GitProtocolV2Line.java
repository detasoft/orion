package pro.deta.orion.git.parser.wire.protocolv2;

import java.util.Objects;
import java.util.Optional;

public record GitProtocolV2Line(String rawLine) {
    public GitProtocolV2Line {
        Objects.requireNonNull(rawLine, "rawLine");
        if (rawLine.indexOf('\n') >= 0 || rawLine.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Protocol v2 line must not contain line endings");
        }
    }

    public String name() {
        int separator = separatorIndex();
        if (separator < 0) {
            return rawLine;
        }
        return rawLine.substring(0, separator);
    }

    public Optional<String> value() {
        int separator = separatorIndex();
        if (separator < 0) {
            return Optional.empty();
        }
        return Optional.of(rawLine.substring(separator + 1));
    }

    private int separatorIndex() {
        int equals = rawLine.indexOf('=');
        int space = rawLine.indexOf(' ');
        if (equals < 0) {
            return space;
        }
        if (space < 0) {
            return equals;
        }
        return Math.min(equals, space);
    }
}
