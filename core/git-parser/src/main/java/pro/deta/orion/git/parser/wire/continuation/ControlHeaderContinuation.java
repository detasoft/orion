package pro.deta.orion.git.parser.wire.continuation;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.ProtocolStage;
import pro.deta.orion.git.parser.wire.error.GitGeneralException;
import pro.deta.orion.git.parser.wire.control.ControlState;
import pro.deta.orion.util.Result;

import java.util.Objects;

import static pro.deta.orion.git.parser.wire.control.ControlState.PKT_LINE_HEADER_SIZE;
import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.PKT_LINE_HEADER_PARSE_FAILURE;

public final class ControlHeaderContinuation implements Continuation<ByteBuf> {
    private final ControlPacketHandler handler;
    private int headerValue;
    private int headerBytes;

    public ControlHeaderContinuation(
            GitMinimalWireMachine.Context context,
            ProtocolStage stage) {
        this(initialRequestHandler(context, stage));
    }

    public ControlHeaderContinuation(ControlPacketHandler handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    @Override
    public ContinuationFlow<ByteBuf> process(ByteBuf input) {
        if (headerBytes == 0
                && input.readableBytes() >= PKT_LINE_HEADER_SIZE) {
            headerValue = input.readInt();
            headerBytes = PKT_LINE_HEADER_SIZE;
        } else {
            while (headerBytes < PKT_LINE_HEADER_SIZE
                    && input.isReadable()) {
                headerValue = (headerValue << 8)
                        | input.readUnsignedByte();
                headerBytes++;
            }
        }
        if (headerBytes < PKT_LINE_HEADER_SIZE) {
            return ContinuationFlow.await();
        }

        Continuation<ByteBuf> next;
        try {
            Result<ControlState> control = ControlState.readControlType(headerValue);
            if (control instanceof Result.Failure<ControlState> failure) {
                return ContinuationFlow.completedError(failure);
            }
            ControlState state = ((Result.Success<ControlState>) control).value();
            next = handler.next(state);
        } catch (Throwable error) {
            next = Continuation.completedError(
                    PKT_LINE_HEADER_PARSE_FAILURE.getMessage(),
                    new GitGeneralException(
                            PKT_LINE_HEADER_PARSE_FAILURE));
        }
        return ContinuationFlow.transition(next);
    }

    private static ControlPacketHandler initialRequestHandler(
            GitMinimalWireMachine.Context context,
            ProtocolStage stage) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(stage, "stage");
        return state -> switch (state.type()) {
            case DATA -> switch (stage) {
                case INITIAL_REQUEST ->
                        new InitialRequestPayloadContinuation(
                                context,
                                state.payloadLength());
                default -> Continuation.completedError(
                        "Payload continuation is not implemented for stage "
                                + stage);
            };
            case FLUSH, DELIMITER, RESPONSE_END ->
                    Continuation.completedError(
                            state.type()
                                    + " is not supported for stage "
                                    + stage);
        };
    }
}
