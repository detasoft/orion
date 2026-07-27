package pro.deta.orion.git.parser.wire.receivepack;

import pro.deta.orion.git.parser.wire.capability.GitCapability;

import java.util.Optional;

public enum ReceivePackCapability {
    REPORT_STATUS("report-status", false, true, true, null),
    SIDE_BAND_64K("side-band-64k", false, true, true, null),
    DELETE_REFS("delete-refs", false, true, true, null),
    OFS_DELTA("ofs-delta", false, true, true, null),
    ATOMIC("atomic", false, true, true, null),
    PUSH_OPTIONS("push-options", false, false, true, null),
    QUIET("quiet", false, false, false, null),
    OBJECT_FORMAT("object-format", true, true, false, "sha1"),
    AGENT("agent", true, true, false, "orion-native/0.1");

    private final String wireName;
    private final boolean valueCapable;
    private final boolean advertised;
    private final boolean requiresExplicitRequest;
    private final String advertisedValue;

    ReceivePackCapability(
            String wireName,
            boolean valueCapable,
            boolean advertised,
            boolean requiresExplicitRequest,
            String advertisedValue) {
        this.wireName = wireName;
        this.valueCapable = valueCapable;
        this.advertised = advertised;
        this.requiresExplicitRequest = requiresExplicitRequest;
        this.advertisedValue = advertisedValue;
    }

    public String wireName() {
        return wireName;
    }

    public boolean valueCapable() {
        return valueCapable;
    }

    public boolean advertised() {
        return advertised;
    }

    public boolean requiresExplicitRequest() {
        return requiresExplicitRequest;
    }

    public Optional<String> advertisedValue() {
        return Optional.ofNullable(advertisedValue);
    }

    public GitCapability toCapability() {
        return advertisedValue != null
                ? GitCapability.of(wireName, advertisedValue)
                : GitCapability.bare(wireName);
    }

    public static Optional<ReceivePackCapability> fromWireName(String name) {
        for (ReceivePackCapability cap : values()) {
            if (cap.wireName.equals(name)) {
                return Optional.of(cap);
            }
        }
        return Optional.empty();
    }
}
