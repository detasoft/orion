package pro.deta.orion.git.parser.wire.continuation.v0v1;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.parser.wire.continuation.ControlHeaderContinuation;
import pro.deta.orion.git.parser.wire.error.GitWireError;

import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.INVALID_LEGACY_RECEIVE_COMMAND;

final class ReceiveCommandPayloadContinuation
        implements Continuation<ByteBuf> {
    private final ReceiveCommandContinuation commands;
    private final byte[] payload;
    private int payloadBytes;

    ReceiveCommandPayloadContinuation(
            ReceiveCommandContinuation commands,
            int payloadLength) {
        this.commands = commands;
        this.payload = new byte[payloadLength];
    }

    @Override
    public ContinuationFlow<ByteBuf> process(ByteBuf input) {
        try {
            int readable = Math.min(
                    input.readableBytes(),
                    payload.length - payloadBytes);
            input.readBytes(payload, payloadBytes, readable);
            payloadBytes += readable;
            if (payloadBytes < payload.length) {
                return ContinuationFlow.await();
            }

            GitWireError.Kind error =
                    commands.acceptCommand(payload);
            if (error != null) {
                return ContinuationFlow.transition(
                        ReceiveCommandContinuation.failed(error));
            }
            return ContinuationFlow.transition(
                    new ControlHeaderContinuation(commands::next));
        } catch (Throwable error) {
            return ContinuationFlow.transition(
                    ReceiveCommandContinuation.failed(
                            INVALID_LEGACY_RECEIVE_COMMAND));
        }
    }
}
