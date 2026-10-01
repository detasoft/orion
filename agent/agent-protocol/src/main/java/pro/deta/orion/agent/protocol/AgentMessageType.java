package pro.deta.orion.agent.protocol;

public enum AgentMessageType {
    HELLO(0x0001, Direction.AGENT_TO_SERVER),
    HEARTBEAT(0x0002, Direction.AGENT_TO_SERVER),
    AGENT_STATUS(0x0003, Direction.AGENT_TO_SERVER),
    SESSION_STATUS(0x0004, Direction.AGENT_TO_SERVER),
    COMMAND_RESULT(0x0005, Direction.AGENT_TO_SERVER),
    SESSION_LIST(0x0006, Direction.AGENT_TO_SERVER),
    SESSION_OPEN(0x0010, Direction.AGENT_TO_SERVER),
    WELCOME(0x8001, Direction.SERVER_TO_AGENT),
    REQUEST_SESSION_LIST(0x8002, Direction.SERVER_TO_AGENT),
    START_SESSION(0x8100, Direction.SERVER_TO_AGENT),
    INPUT(0x8101, Direction.SERVER_TO_AGENT),
    RESIZE(0x8102, Direction.SERVER_TO_AGENT),
    SIGNAL(0x8103, Direction.SERVER_TO_AGENT),
    TERMINATE(0x8104, Direction.SERVER_TO_AGENT),
    SESSION_SYNC(0x8110, Direction.SERVER_TO_AGENT);

    private final int code;
    private final Direction direction;

    AgentMessageType(int code, Direction direction) {
        this.code = code;
        this.direction = direction;
    }

    public int code() {
        return code;
    }

    public Direction direction() {
        return direction;
    }

    public static AgentMessageType fromCode(int code) {
        for (AgentMessageType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return null;
    }

    public enum Direction {
        AGENT_TO_SERVER,
        SERVER_TO_AGENT
    }
}
