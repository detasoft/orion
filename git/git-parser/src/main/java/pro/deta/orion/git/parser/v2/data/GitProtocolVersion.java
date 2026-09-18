package pro.deta.orion.git.parser.v2.data;

public enum GitProtocolVersion {
    V0("0"),
    V1("1"),
    V2("2");

    private final String wireValue;

    GitProtocolVersion(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static GitProtocolVersion fromWireValue(String wireValue) {
        for (GitProtocolVersion version : values()) {
            if (version.wireValue.equals(wireValue)) {
                return version;
            }
        }
        throw new IllegalArgumentException(
                "Unsupported Git protocol version '" + wireValue + "'");
    }
}
