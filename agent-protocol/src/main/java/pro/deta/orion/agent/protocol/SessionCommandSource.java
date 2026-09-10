package pro.deta.orion.agent.protocol;

public enum SessionCommandSource {
    SERVER(1),
    MANUAL(2);

    private final int wireCode;

    SessionCommandSource(int wireCode) {
        this.wireCode = wireCode;
    }

    public int wireCode() {
        return wireCode;
    }

    static SessionCommandSource fromWireCode(int wireCode) {
        for (SessionCommandSource value : values()) {
            if (value.wireCode == wireCode) {
                return value;
            }
        }
        return null;
    }
}
