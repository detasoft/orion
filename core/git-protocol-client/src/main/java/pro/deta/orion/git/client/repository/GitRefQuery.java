package pro.deta.orion.git.client.repository;

import java.util.List;
import java.util.Objects;

public record GitRefQuery(List<String> prefixes) {
    public GitRefQuery {
        prefixes = List.copyOf(Objects.requireNonNull(prefixes, "prefixes"));
        for (String prefix : prefixes) {
            Objects.requireNonNull(prefix, "prefix");
        }
    }

    public boolean matches(String refName) {
        Objects.requireNonNull(refName, "refName");
        if (prefixes.isEmpty()) {
            return true;
        }
        for (String prefix : prefixes) {
            if (refName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
