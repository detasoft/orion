package pro.deta.orion.git.parser.v2.id;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Immutable SHA-1 identity stored as twenty bytes, with canonical hexadecimal formatting.
 * Equality includes the concrete ID type so pack, commit, and general object IDs remain distinct.
 */
public abstract sealed class GitId permits PackId, CommitId, ObjectId {
    private static final int BYTE_LENGTH = 20;
    private final byte[] bytes;

    GitId(String hex) {
        this(parseHex(hex));
    }

    GitId(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length != BYTE_LENGTH) {
            throw new IllegalArgumentException("Git SHA-1 ID must contain 20 bytes");
        }
        this.bytes = bytes.clone();
    }

    public final byte[] toBytes() {
        return bytes.clone();
    }

    public final String toHex() {
        return HexFormat.of().formatHex(bytes);
    }

    @Override
    public final String toString() {
        return toHex();
    }

    @Override
    public final boolean equals(Object other) {
        return this == other || other != null && getClass() == other.getClass()
                && Arrays.equals(bytes, ((GitId) other).bytes);
    }

    @Override
    public final int hashCode() {
        return 31 * getClass().hashCode() + Arrays.hashCode(bytes);
    }

    private static byte[] parseHex(String hex) {
        Objects.requireNonNull(hex, "hex");
        if (hex.length() != BYTE_LENGTH * 2) {
            throw new IllegalArgumentException("Git SHA-1 ID must contain 40 hexadecimal characters");
        }
        return HexFormat.of().parseHex(hex);
    }
}
