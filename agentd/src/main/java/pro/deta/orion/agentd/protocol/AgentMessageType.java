package pro.deta.orion.agentd.protocol;

public enum AgentMessageType {
    HELLO(0x0001),
    HEARTBEAT(0x0002),
    AGENT_STATUS(0x0003),
    SESSION_STATUS(0x0004),
    COMMAND_RESULT(0x0005),
    SESSION_EVENTS(0x0010),
    SESSION_GAP(0x0011),
    WELCOME(0x8001),
    START_SESSION(0x8100),
    INPUT(0x8101),
    RESIZE(0x8102),
    SIGNAL(0x8103),
    TERMINATE(0x8104),
    SESSION_ACK(0x8110),
    RESUME_SESSION(0x8111);

    private final int code;

    AgentMessageType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static AgentMessageType fromCode(int code) {
        for (AgentMessageType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return null;
    }
}
