package pro.deta.orion.git.parser.wire.continuation.v0v1;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;

import java.util.Objects;

import static pro.deta.orion.git.parser.wire.continuation.OutputTransitions.transitionAfterOutput;

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
        return transitionAfterOutput(
                context.clientOutput::sendNak,
                negotiation,
                "Failed to write legacy upload-pack negotiation response");
    }
}
