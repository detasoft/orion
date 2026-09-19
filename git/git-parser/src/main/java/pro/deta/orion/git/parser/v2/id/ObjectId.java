package pro.deta.orion.git.parser.v2.id;

public final class ObjectId extends GitId {
    public ObjectId(byte[] bytes) {
        super(bytes);
    }

    public ObjectId(String hex) {
        super(hex);
    }
}
