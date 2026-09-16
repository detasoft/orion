package pro.deta.orion.git.parser.v2.fetch;

import pro.deta.orion.git.parser.wire.capability.GitCapability;

import java.io.IOException;
import java.util.Objects;

/**
 * One decoded fetch argument or negotiation message, with a GitCapability name and its value.
 * parse splits packet text at the first space and requires a nonempty value for argument-bearing names.
 * Bare flags match exactly and have an empty value. The full remainder is preserved for Unicode refs and
 * legacy want capabilities. Protocol-specific use and value validation belong to FetchNegotiator.
 * Capability tokens using '=' still use GitCapability.parse. Each parsed value belongs to its own instance.
 */
public record NegotiationCapability(GitCapability cap, String value) {
    public NegotiationCapability {
        Objects.requireNonNull(cap, "cap");
        Objects.requireNonNull(value, "value");
    }

    public static NegotiationCapability parse(String line) throws IOException {
        int separator = line.indexOf(' ');
        String name = separator < 0 ? line : line.substring(0, separator);
        GitCapability cap = GitCapability.fromWireName(name)
                .orElseThrow(() -> new IOException("Invalid negotiation argument: " + line));
        return switch (cap) {
            case WANT, WANT_REF, HAVE, SHALLOW, DEEPEN, DEEPEN_SINCE, DEEPEN_NOT, FILTER, PACKFILE_URIS -> {
                if (separator < 0 || separator == line.length() - 1) {
                    throw new IOException("Expected a value for " + name);
                }
                yield new NegotiationCapability(cap, line.substring(separator + 1));
            }
            case DONE, THIN_PACK, OFS_DELTA, INCLUDE_TAG, NO_PROGRESS, WAIT_FOR_DONE,
                 SIDEBAND_ALL, DEEPEN_RELATIVE -> {
                if (separator >= 0) {
                    throw new IOException("Unexpected value for " + name);
                }
                yield new NegotiationCapability(cap, "");
            }
            default -> throw new IOException("Invalid negotiation argument: " + line);
        };
    }
}
