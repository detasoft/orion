package pro.deta.orion.git.parser.v2.id;

import java.util.Objects;
import java.util.UUID;

/** Internal pack identity allocated before ingestion, independent of the Git checksum. */
public record PackId(UUID value) {
    public PackId {
        Objects.requireNonNull(value, "value");
    }

    public PackId(String value) {
        this(UUID.fromString(value));
    }

    public static PackId create() {
        return new PackId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
