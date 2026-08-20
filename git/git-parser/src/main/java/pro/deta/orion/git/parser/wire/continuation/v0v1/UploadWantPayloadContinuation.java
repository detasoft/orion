package pro.deta.orion.git.parser.wire.continuation.v0v1;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.parser.wire.continuation.ControlHeaderContinuation;
import pro.deta.orion.git.parser.wire.error.GitWireError;

import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.INVALID_LEGACY_UPLOAD_REQUEST;

final class UploadWantPayloadContinuation
        implements Continuation<ByteBuf> {
    private final UploadRequestContinuation request;
    private final byte[] payload;
    private int payloadBytes;

    UploadWantPayloadContinuation(
            UploadRequestContinuation request,
            int payloadLength) {
        this.request = request;
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

            GitWireError.Kind error = request.acceptWant(payload);
            if (error != null) {
                return ContinuationFlow.transition(
                        UploadRequestContinuation.failed(error));
            }
            return ContinuationFlow.transition(
                    new ControlHeaderContinuation(request::next));
        } catch (Throwable error) {
            return ContinuationFlow.transition(
                    UploadRequestContinuation.failed(
                            INVALID_LEGACY_UPLOAD_REQUEST));
        }
    }
}
