package pro.deta.orion.git.parser.v2.data;

/**
 * Identifies an object type or delta encoding using the numeric code from a Git pack entry header.
 * Codes 0 and 5 are not valid entry types. code() returns the wire value, independently of enum ordinal.
 * Restored objects use only COMMIT, TREE, BLOB, or TAG. OFS_DELTA and REF_DELTA describe packed
 * representations whose logical type is inherited from the resolved base object.
 */
public enum ObjectType {
    COMMIT(1),
    TREE(2),
    BLOB(3),
    TAG(4),
    OFS_DELTA(6),
    REF_DELTA(7);

    private final int code;

    ObjectType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
