package pro.deta.orion.git.parser.wire;

public enum ProtocolStage {
    INITIAL_REQUEST,
    UPLOAD_V0_WANTS,
    UPLOAD_V0_HAVES,
    UPLOAD_V2_COMMAND,
    UPLOAD_V2_ARGUMENTS,
    RECEIVE_COMMANDS
}
