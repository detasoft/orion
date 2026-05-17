package pro.deta.orion.resource.address;

import java.util.ArrayList;
import java.util.List;

/**
 * Catalog of semantic target types returned by the standard resource-addressing resolver.
 */
public enum ResourceAddressTargetType {
    IMMUTABLE_REFERENCE(Kind.VALUE, ImmutableReference.class),
    MUTABLE_REFERENCE(Kind.REFERENCE, MutableReference.class);

    private final Kind kind;
    private final Class<?> type;

    ResourceAddressTargetType(Kind kind, Class<?> type) {
        this.kind = kind;
        this.type = type;
    }

    public Kind kind() {
        return kind;
    }

    public Class<?> type() {
        return type;
    }

    public static List<Class<?>> standardTypes() {
        List<Class<?>> types = new ArrayList<>();
        for (ResourceAddressTargetType targetType : values()) {
            types.add(targetType.type());
        }
        return List.copyOf(types);
    }

    public enum Kind {
        VALUE,
        REFERENCE
    }
}
