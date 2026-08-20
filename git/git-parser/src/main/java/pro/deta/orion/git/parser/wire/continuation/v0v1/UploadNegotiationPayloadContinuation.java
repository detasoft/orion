package pro.deta.orion.git.parser.wire.continuation.v0v1;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;

import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.INVALID_LEGACY_UPLOAD_NEGOTIATION;

final class UploadNegotiationPayloadContinuation
        implements Continuation<ByteBuf> {
    private final UploadNegotiationContinuation negotiation;
    private final byte[] payload;
    private int payloadBytes;

    UploadNegotiationPayloadContinuation(
            UploadNegotiationContinuation negotiation,
            int payloadLength) {
        this.negotiation = negotiation;
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
            return ContinuationFlow.transition(
                    negotiation.accept(payload));
        } catch (Throwable error) {
            return ContinuationFlow.transition(
                    UploadNegotiationContinuation.failed(
                            INVALID_LEGACY_UPLOAD_NEGOTIATION));
        }
    }
}
