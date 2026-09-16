package pro.deta.orion.git.parser.wire.capability;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Known capability, fetch argument, and negotiation message names. Wire spelling differs from Enum.name(). A runtime
 * value belongs to Entry, never to the shared enum instance. STANDARD_NAMES defines which known names must
 * not be created as custom extensions; membership is independent of the capabilities allowed by a command.
 * parse reads a bare or valued wire token, splitting only at the first '=' and preserving custom names.
 * Entry validates syntax; parse also checks an explicit object-format value against expectedObjectFormat.
 * Invalid tokens and mismatched formats are IOException. Other capability support remains caller policy.
 */
public enum GitCapability {
    WANT_REF("want-ref"),
    WANT("want"),
    HAVE("have"),
    DONE("done"),
    MULTI_ACK("multi_ack"),
    MULTI_ACK_DETAILED("multi_ack_detailed"),
    NO_DONE("no-done"),
    WAIT_FOR_DONE("wait-for-done"),
    SIDEBAND_ALL("sideband-all"),
    PACKFILE_URIS("packfile-uris"),
    THIN_PACK("thin-pack"),
    NO_THIN("no-thin"),
    SIDE_BAND("side-band"),
    SIDE_BAND_64K("side-band-64k"),
    OFS_DELTA("ofs-delta"),
    AGENT("agent"),
    OBJECT_FORMAT("object-format"),
    SYMREF("symref"),
    SHALLOW("shallow"),
    DEEPEN("deepen"),
    DEEPEN_SINCE("deepen-since"),
    DEEPEN_NOT("deepen-not"),
    DEEPEN_RELATIVE("deepen-relative"),
    NO_PROGRESS("no-progress"),
    INCLUDE_TAG("include-tag"),
    REPORT_STATUS("report-status"),
    REPORT_STATUS_V2("report-status-v2"),
    DELETE_REFS("delete-refs"),
    QUIET("quiet"),
    ATOMIC("atomic"),
    PUSH_OPTIONS("push-options"),
    ALLOW_TIP_SHA1_IN_WANT("allow-tip-sha1-in-want"),
    ALLOW_REACHABLE_SHA1_IN_WANT("allow-reachable-sha1-in-want"),
    PUSH_CERT("push-cert"),
    FILTER("filter"),
    REF_IN_WANT("ref-in-want"),
    SESSION_ID("session-id");

    private static final Set<GitCapability> STANDARD_NAMES = Set.of(
            MULTI_ACK, MULTI_ACK_DETAILED, NO_DONE, WAIT_FOR_DONE, SIDEBAND_ALL, PACKFILE_URIS,
            THIN_PACK, NO_THIN, SIDE_BAND, SIDE_BAND_64K, OFS_DELTA, AGENT, OBJECT_FORMAT,
            SYMREF, SHALLOW, DEEPEN_SINCE, DEEPEN_NOT, DEEPEN_RELATIVE, NO_PROGRESS, INCLUDE_TAG,
            REPORT_STATUS, REPORT_STATUS_V2, DELETE_REFS, QUIET, ATOMIC, PUSH_OPTIONS,
            ALLOW_TIP_SHA1_IN_WANT, ALLOW_REACHABLE_SHA1_IN_WANT, PUSH_CERT, FILTER, REF_IN_WANT, SESSION_ID);

    private final String wireName;

    GitCapability(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public Entry entry() {
        return new Entry(wireName, Optional.empty());
    }

    public Entry withValue(String value) {
        return new Entry(wireName, Optional.of(Objects.requireNonNull(value, "value")));
    }

    public static Entry parse(String wireToken, GitObjectFormat expectedObjectFormat) throws IOException {
        Objects.requireNonNull(wireToken, "wireToken");
        Objects.requireNonNull(expectedObjectFormat, "expectedObjectFormat");
        Entry entry;
        try {
            int separator = wireToken.indexOf('=');
            entry = separator < 0 ? new Entry(wireToken, Optional.empty())
                    : new Entry(wireToken.substring(0, separator), Optional.of(wireToken.substring(separator + 1)));
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid capability", error);
        }
        if (entry.name().equals(OBJECT_FORMAT.wireName()) && entry.value().isPresent()
                && !entry.value().get().equals(expectedObjectFormat.wireName())) {
            throw new IOException("Expected object format " + expectedObjectFormat.wireName()
                    + ", received " + entry.value().get());
        }
        return entry;
    }

    public static Optional<GitCapability> fromWireName(String name) {
        Objects.requireNonNull(name, "name");
        for (GitCapability capability : values()) {
            if (capability.wireName.equals(name)) {
                return Optional.of(capability);
            }
        }
        return Optional.empty();
    }

    /**
     * One immutable bare or valued capability token, including custom extensions not listed in the enum.
     * Known names are normally created with enum.entry()/withValue(); custom rejects STANDARD_NAMES.
     * Different agent, symref, and other values coexist without modifying shared enum constants.
     * Validation preserves the existing name/value contract; wireToken serializes name[=value].
     */
    public record Entry(String name, Optional<String> value) {
        public Entry {
            name = validateName(name);
            value = Objects.requireNonNull(value, "value").map(Entry::validateValue);
        }

        public static Entry custom(String name) {
            return custom(name, Optional.empty());
        }

        public static Entry custom(String name, String value) {
            return custom(name, Optional.of(Objects.requireNonNull(value, "value")));
        }

        public String wireToken() {
            return value.map(item -> name + "=" + item).orElse(name);
        }

        private static Entry custom(String name, Optional<String> value) {
            String checkedName = validateName(name);
            if (fromWireName(checkedName).filter(STANDARD_NAMES::contains).isPresent()) {
                throw new IllegalArgumentException("Standard capability must use its enum instance");
            }
            return new Entry(checkedName, value);
        }

        private static String validateName(String name) {
            String checked = Objects.requireNonNull(name, "name");
            if (checked.isEmpty()) {
                throw new IllegalArgumentException("Capability name must not be empty");
            }
            for (int index = 0; index < checked.length(); index++) {
                char character = checked.charAt(index);
                if (Character.isWhitespace(character) || character == '=') {
                    throw new IllegalArgumentException("Capability name must not contain whitespace or '='");
                }
            }
            return checked;
        }

        private static String validateValue(String value) {
            String checked = Objects.requireNonNull(value, "value");
            if (checked.isEmpty()) {
                throw new IllegalArgumentException("Capability value must not be empty");
            }
            for (int index = 0; index < checked.length(); index++) {
                char character = checked.charAt(index);
                if (character <= 32 || character >= 127) {
                    throw new IllegalArgumentException("Capability value must contain printable non-space ASCII");
                }
            }
            return checked;
        }
    }
}
