package pro.deta.orion.command;

import java.util.List;
import java.util.Objects;

public sealed interface CommandNavigation {
    record Located(CommandLocation location) implements CommandNavigation {
        public Located {
            Objects.requireNonNull(location, "location");
        }
    }

    record Missing() implements CommandNavigation {}

    record UnknownPath() implements CommandNavigation {}

    record Ambiguous(List<String> candidates) implements CommandNavigation {
        public Ambiguous {
            Objects.requireNonNull(candidates, "candidates");
            candidates = List.copyOf(candidates);
        }
    }
}
