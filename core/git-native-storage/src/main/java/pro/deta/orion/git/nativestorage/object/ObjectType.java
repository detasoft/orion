package pro.deta.orion.git.nativestorage.object;

public enum ObjectType {
    COMMIT(1),
    TREE(2),
    BLOB(3),
    TAG(4);

    private final int packTypeId;

    ObjectType(int packTypeId) {
        this.packTypeId = packTypeId;
    }

    public int packTypeId() {
        return packTypeId;
    }

    public String headerName() {
        return name().toLowerCase();
    }

    public static ObjectType fromPackTypeId(int typeId) {
        for (ObjectType type : values()) {
            if (type.packTypeId == typeId) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown pack object type id: " + typeId);
    }
}
