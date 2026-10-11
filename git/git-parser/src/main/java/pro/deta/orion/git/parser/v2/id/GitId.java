package pro.deta.orion.git.parser.v2.id;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Immutable Git hash stored as twenty or thirty-two bytes, with canonical hexadecimal formatting.
 * The repository selects the hash algorithm and validates the corresponding length.
 * Object IDs share equality by hash, including commit IDs; pack checksums remain distinct.
 */
public abstract sealed class GitId permits PackChecksum, ObjectId {
    private final byte[] bytes;

    GitId(String hex) {
        this(parseHex(hex));
    }

    GitId(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length != 20 && bytes.length != 32) {
            throw new IllegalArgumentException("Git ID must contain 20 or 32 bytes");
        }
        this.bytes = bytes.clone();
    }

    public final int byteLength() {
        return bytes.length;
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
        return this == other || other instanceof GitId that
                && (this instanceof ObjectId) == (that instanceof ObjectId)
                && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public final int hashCode() {
        Class<?> type = this instanceof ObjectId ? ObjectId.class : PackChecksum.class;
        return 31 * type.hashCode() + Arrays.hashCode(bytes);
    }

    private static byte[] parseHex(String hex) {
        Objects.requireNonNull(hex, "hex");
        if (hex.length() != 40 && hex.length() != 64) {
            throw new IllegalArgumentException("Git ID must contain 40 or 64 hexadecimal characters");
        }
        return HexFormat.of().parseHex(hex);
    }
}
