package pro.deta.orion.git.parser.v2.id;

/**
 * Identifies a Git commit; the value alone does not verify the referenced object's type.
 */
public final class CommitId extends GitId {
    public CommitId(byte[] bytes) {
        super(bytes);
    }

    public CommitId(String hex) {
        super(hex);
    }
}
