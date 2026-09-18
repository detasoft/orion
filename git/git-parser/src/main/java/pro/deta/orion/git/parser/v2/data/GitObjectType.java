package pro.deta.orion.git.parser.v2.data;

import java.io.IOException;

public enum GitObjectType {
    COMMIT(1),
    TREE(2),
    BLOB(3),
    TAG(4),
    OFS_DELTA(6),
    REF_DELTA(7);

    private final int code;

    GitObjectType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static GitObjectType valueOf(int code) throws IOException {
        GitObjectType[] values = GitObjectType.values();
        for (GitObjectType value : values) {
            if (value.code == code)
                return value;
        }
        throw new IOException("Invalid object type code: " + code);
    }
}
