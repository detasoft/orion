package pro.deta.orion.git.parser.v2.fetch;

import java.util.Objects;
import java.util.Optional;

/**
 * Fetch argument and negotiation message names that are not advertised capabilities.
 * Each constant stores only its protocol name; values belong to individual requests and messages.
 */
public enum NegotiationCapability {
    WANT_REF("want-ref"),
    WANT("want"),
    HAVE("have"),
    DONE("done"),
    DEEPEN("deepen");

    private final String wireName;

    NegotiationCapability(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Optional<NegotiationCapability> findByWireName(String wireName) {
        Objects.requireNonNull(wireName, "wireName");
        for (NegotiationCapability capability : values()) {
            if (capability.wireName.equals(wireName)) {
                return Optional.of(capability);
            }
        }
        return Optional.empty();
    }
}
