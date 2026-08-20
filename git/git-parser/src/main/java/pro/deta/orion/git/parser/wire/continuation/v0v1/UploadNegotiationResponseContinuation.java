package pro.deta.orion.git.parser.wire.continuation.v0v1;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.GitNativeClientOutput;

import java.util.Objects;

final class UploadNegotiationResponseContinuation
        implements Continuation<ByteBuf> {
    private final GitMinimalWireMachine.Context context;
    private final UploadNegotiationContinuation negotiation;

    UploadNegotiationResponseContinuation(
            GitMinimalWireMachine.Context context,
            UploadNegotiationContinuation negotiation) {
        this.context = Objects.requireNonNull(context, "context");
        this.negotiation = Objects.requireNonNull(
                negotiation,
                "negotiation");
    }

    @Override
    public ContinuationFlow<ByteBuf> process(ByteBuf input) {
        try {
            GitNativeClientOutput.SendResult result =
                    context.clientOutput.sendNak();
            return result.transitionTo(negotiation);
        } catch (RuntimeException error) {
            return ContinuationFlow.completedError(
                    "Failed to write legacy upload-pack negotiation response",
                    error);
        }
    }
}
