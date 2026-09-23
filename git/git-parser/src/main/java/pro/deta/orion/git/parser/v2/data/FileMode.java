package pro.deta.orion.git.parser.v2.data;

public enum FileMode {
    REGULAR_FILE(0100644),
    EXECUTABLE_FILE(0100755),
    SYMLINK(0120000),
    TREE(0040000),
    GITLINK(0160000);

    private final int code;

    FileMode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static FileMode fromCode(int code) {
        for (FileMode mode : values()) {
            if (mode.code == code) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unsupported Git file mode: " + Integer.toOctalString(code));
    }
}
