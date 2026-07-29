package pro.deta.orion.git.parser.wire.continuation;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.ProtocolStage;
import pro.deta.orion.git.parser.wire.error.GitGeneralException;
import pro.deta.orion.git.parser.wire.control.ControlState;
import pro.deta.orion.util.Result;

import static pro.deta.orion.git.parser.wire.control.ControlState.PKT_LINE_HEADER_SIZE;
import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.SOME_NAME;

public final class ControlHeaderContinuation implements Continuation<ByteBuf> {
    private final GitMinimalWireMachine.Context context;
    private final ProtocolStage stage;

    public ControlHeaderContinuation(
            GitMinimalWireMachine.Context context,
            ProtocolStage stage) {
        this.context = context;
        this.stage = stage;
    }

    @Override
    public ContinuationFlow<ByteBuf> process(ByteBuf input) {
        if (input.readableBytes() < PKT_LINE_HEADER_SIZE)
            return ContinuationFlow.await();

        Continuation<ByteBuf> next;
        try {

            int headerValue = input.readInt();
            Result<ControlState> control = ControlState.readControlType(headerValue);

            switch (control) {
                case Result.Failure<ControlState> failure -> {
                    return ContinuationFlow.completedError(failure);
                }
                case Result.Success<ControlState>(var state) -> {
                    next = switch(state.type()) {
                        case DATA -> switch (stage) {
                            case INITIAL_REQUEST ->
                                    new InitialRequestPayloadContinuation(
                                            context,
                                            state.payloadLength());
                            default ->
                                    Continuation.completedError(
                                            "Payload continuation is not implemented for stage " + stage);
                        };
                        default ->
                            Continuation.completedError("Not supported");
                    };
                }
            }
        } catch (Throwable error) {
            next = Continuation.completedError(SOME_NAME.getMessage(), new GitGeneralException(SOME_NAME));
        }
        return ContinuationFlow.transition(next);
    }

}
