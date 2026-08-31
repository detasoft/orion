package pro.deta.orion.git.parser.wire.exchange;

import java.util.List;
import java.util.Objects;

public record LsRefsRequest(
        boolean peel,
        boolean symrefs,
        boolean unborn,
        List<String> refPrefixes) {

    public LsRefsRequest {
        Objects.requireNonNull(refPrefixes, "refPrefixes");
        refPrefixes = List.copyOf(refPrefixes);
    }

    public boolean matches(String refName) {
        Objects.requireNonNull(refName, "refName");
        if (refPrefixes.isEmpty()) {
            return true;
        }
        for (String prefix : refPrefixes) {
            if (refName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
