package pro.deta.orion.agent.protocol;

public final class SessionEventType {
    public static final int PTY_OUTPUT = 0x0100;
    public static final int PTY_INPUT = 0x0101;
    public static final int PTY_RESIZE = 0x0102;
    public static final int PROCESS_EXITED = 0x0201;

    private SessionEventType() {
    }
}
