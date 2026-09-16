package pro.deta.orion.git.parser.v2.fetch;

import pro.deta.orion.git.parser.wire.capability.GitCapability;

import java.io.IOException;

/**
 * Fetch argument spellings derived from GitCapability, including the space before a required value.
 * Bare flags match exactly. parse consumes decoded packet text and returns the capability with its value,
 * preserving the full remainder for Unicode refs and legacy want capabilities. Protocol-specific use and
 * value validation belong to FetchNegotiator. Capability tokens using '=' still use GitCapability.parse.
 */
public enum NegotiationCapability {
    WANT(GitCapability.WANT, " "),
    WANT_REF(GitCapability.WANT_REF, " "),
    HAVE(GitCapability.HAVE, " "),
    SHALLOW(GitCapability.SHALLOW, " "),
    DEEPEN(GitCapability.DEEPEN, " "),
    DEEPEN_SINCE(GitCapability.DEEPEN_SINCE, " "),
    DEEPEN_NOT(GitCapability.DEEPEN_NOT, " "),
    FILTER(GitCapability.FILTER, " "),
    PACKFILE_URIS(GitCapability.PACKFILE_URIS, " "),
    DONE(GitCapability.DONE, ""),
    THIN_PACK(GitCapability.THIN_PACK, ""),
    OFS_DELTA(GitCapability.OFS_DELTA, ""),
    INCLUDE_TAG(GitCapability.INCLUDE_TAG, ""),
    NO_PROGRESS(GitCapability.NO_PROGRESS, ""),
    WAIT_FOR_DONE(GitCapability.WAIT_FOR_DONE, ""),
    SIDEBAND_ALL(GitCapability.SIDEBAND_ALL, ""),
    DEEPEN_RELATIVE(GitCapability.DEEPEN_RELATIVE, "");

    private final GitCapability cap;
    private final String wireValue;

    NegotiationCapability(GitCapability cap, String separator) {
        this.cap = cap;
        this.wireValue = cap.wireName() + separator;
    }

    public static NegotiationCapabilityValue parse(String line) throws IOException {
        for (NegotiationCapability capability : values()) {
            if (capability.wireValue.endsWith(" ")) {
                if (line.startsWith(capability.wireValue) && line.length() > capability.wireValue.length()) {
                    return new NegotiationCapabilityValue(capability.cap,
                            line.substring(capability.wireValue.length()));
                }
            } else if (line.equals(capability.wireValue)) {
                return new NegotiationCapabilityValue(capability.cap, "");
            }
        }
        throw new IOException("Invalid negotiation argument: " + line);
    }
}
