package pro.deta.orion.git.parser.v2.lsrefs;

import java.util.Objects;
import java.util.Optional;

public enum LsRefsArgument {
    SYMREFS("symrefs"),
    PEEL("peel"),
    UNBORN("unborn"),
    REF_PREFIX("ref-prefix");

    private final String wireName;

    LsRefsArgument(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Optional<LsRefsArgument> findByWireName(String wireName) {
        Objects.requireNonNull(wireName, "wireName");
        for (LsRefsArgument argument : values()) {
            if (argument.wireName.equals(wireName)) {
                return Optional.of(argument);
            }
        }
        return Optional.empty();
    }
}
