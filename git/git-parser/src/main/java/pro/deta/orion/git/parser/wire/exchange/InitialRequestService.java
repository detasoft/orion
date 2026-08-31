package pro.deta.orion.git.parser.wire.exchange;

public enum InitialRequestService {
    UPLOAD_PACK("git-upload-pack"),
    RECEIVE_PACK("git-receive-pack");

    private final String wireName;

    InitialRequestService(String wireName) {
        this.wireName = wireName;
    }

    public static InitialRequestService fromWireName(String wireName) {
        for (InitialRequestService service : values()) {
            if (service.wireName.equals(wireName)) {
                return service;
            }
        }
        throw new IllegalArgumentException("Unsupported Git service: " + wireName);
    }

    public String wireName() {
        return wireName;
    }
}
