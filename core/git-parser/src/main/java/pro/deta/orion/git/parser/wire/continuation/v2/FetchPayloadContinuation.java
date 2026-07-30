package pro.deta.orion.git.parser.wire.continuation.v2;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.parser.wire.continuation.ControlHeaderContinuation;

final class FetchPayloadContinuation implements Continuation<ByteBuf> {
    private final FetchContinuation fetch;
    private final byte[] payload;
    private int payloadBytes;

    FetchPayloadContinuation(
            FetchContinuation fetch,
            int payloadLength) {
        this.fetch = fetch;
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
            if (!fetch.accept(payload)) {
                return ContinuationFlow.transition(
                        FetchContinuation.failed());
            }
            return ContinuationFlow.transition(
                    new ControlHeaderContinuation(fetch::next));
        } catch (Throwable error) {
            return ContinuationFlow.transition(
                    FetchContinuation.failed());
        }
    }
}
