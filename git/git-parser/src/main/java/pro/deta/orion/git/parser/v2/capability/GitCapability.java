package pro.deta.orion.git.parser.v2.capability;

import java.util.Objects;
import java.util.Optional;

public enum GitCapability {
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

    private final String wireName;

    GitCapability(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Optional<GitCapability> findByWireName(String wireName) {
        Objects.requireNonNull(wireName, "wireName");
        for (GitCapability capability : values()) {
            if (capability.wireName.equals(wireName)) {
                return Optional.of(capability);
            }
        }
        return Optional.empty();
    }
}
