package pro.deta.orion.git.parser.wire.continuation.v2;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.control.ControlState;
import pro.deta.orion.git.parser.wire.continuation.ControlHeaderContinuation;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.error.GitGeneralException;

import java.util.Objects;

import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.INVALID_PROTOCOL_V2_REQUEST;

final class UploadCommandContinuation implements Continuation<ByteBuf> {
    private final GitMinimalWireMachine.Context context;
    private final InitialRequestData data;
    private Command command;

    UploadCommandContinuation(
            GitMinimalWireMachine.Context context,
            InitialRequestData data) {
        this.context = Objects.requireNonNull(context, "context");
        this.data = Objects.requireNonNull(data, "data");
    }

    @Override
    public ContinuationFlow<ByteBuf> process(ByteBuf input) {
        return ContinuationFlow.transition(
                new ControlHeaderContinuation(this::next));
    }

    Continuation<ByteBuf> next(ControlState control) {
        return switch (control.type()) {
            case DATA -> control.payloadLength() == 0
                    ? failed()
                    : command == null
                            ? new UploadCommandPayloadContinuation(
                                    this,
                                    control.payloadLength())
                            : failed();
            case DELIMITER -> completeCommand();
            case FLUSH -> failed();
            case RESPONSE_END -> failed();
        };
    }

    boolean acceptCommand(Command command) {
        if (this.command != null) {
            return false;
        }
        this.command = Objects.requireNonNull(command, "command");
        return true;
    }

    private Continuation<ByteBuf> completeCommand() {
        if (command == null) {
            return failed();
        }
        return switch (command) {
            case LS_REFS -> new LsRefsContinuation(context, data);
            case FETCH -> new FetchContinuation(context, data);
        };
    }

    static Continuation<ByteBuf> failed() {
        return Continuation.completedError(
                INVALID_PROTOCOL_V2_REQUEST.getMessage(),
                new GitGeneralException(INVALID_PROTOCOL_V2_REQUEST));
    }

    enum Command {
        LS_REFS,
        FETCH
    }
}
