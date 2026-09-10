package pro.deta.orion.agent.protocol;

public enum SessionCommandOutcome {
    SUCCEEDED(1),
    FAILED(2),
    REJECTED(3),
    AMBIGUOUS(4);

    private final int wireCode;

    SessionCommandOutcome(int wireCode) {
        this.wireCode = wireCode;
    }

    public int wireCode() {
        return wireCode;
    }

    static SessionCommandOutcome fromWireCode(int wireCode) {
        for (SessionCommandOutcome value : values()) {
            if (value.wireCode == wireCode) {
                return value;
            }
        }
        return null;
    }
}
