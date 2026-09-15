package pro.deta.orion.git.parser.v2.data;

/**
 * Identifies the canonical mode of a Git tree entry, combining its kind and tracked permission bits.
 * REGULAR_FILE, EXECUTABLE_FILE, and SYMLINK reference blobs; TREE references a tree, and GITLINK
 * references a commit in a submodule. The mode belongs to the tree entry, not the referenced object.
 * code() returns the numeric mode bits. Constants use Java octal literals; Integer.toOctalString(code())
 * formats the mode for a tree entry, for example "100644" for a regular file and "40000" for a tree.
 */
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
}
