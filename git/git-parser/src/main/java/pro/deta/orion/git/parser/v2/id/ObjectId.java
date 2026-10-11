package pro.deta.orion.git.parser.v2.id;

public sealed class ObjectId extends GitId permits ObjectId.CommitId {
    public ObjectId(byte[] bytes) {
        super(bytes);
    }

    public ObjectId(String hex) {
        super(hex);
    }

    /** Identifies a commit; reading the object still verifies its actual type. */
    public static final class CommitId extends ObjectId {
        public CommitId(byte[] bytes) {
            super(bytes);
        }

        public CommitId(String hex) {
            super(hex);
        }
    }
}
