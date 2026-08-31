package pro.deta.orion.git.parser.wire.continuation.v2;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.nativestorage.upload.NativeFetchRequest;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.lifecycle.state.TestOnly;

import java.util.Objects;

import static pro.deta.orion.git.parser.wire.continuation.OutputTransitions.transitionAfterOutput;

final class FetchNegotiationResponseContinuation
        implements Continuation<ByteBuf> {
    private final GitMinimalWireMachine.Context context;
    private final InitialRequestData data;
    private final NativeFetchRequest request;
    private final boolean sidebandAll;

    FetchNegotiationResponseContinuation(
            GitMinimalWireMachine.Context context,
            InitialRequestData data,
            NativeFetchRequest request,
            boolean sidebandAll) {
        this.context = Objects.requireNonNull(context, "context");
        this.data = Objects.requireNonNull(data, "data");
        this.request = Objects.requireNonNull(request, "request");
        this.sidebandAll = sidebandAll;
    }

    @Override
    public ContinuationFlow<ByteBuf> process(ByteBuf input) {
        return transitionAfterOutput(
                () -> context.clientOutput.sendProtocolV2FetchAcknowledgments(
                        context.repositoryService
                                .protocolV2FetchAcknowledgments(
                                        data,
                                        request),
                        sidebandAll),
                new UploadCommandContinuation(context, data),
                "Failed to write protocol v2 fetch negotiation response");
    }

    @TestOnly
    NativeFetchRequest request() {
        return request;
    }

    @TestOnly
    boolean sidebandAll() {
        return sidebandAll;
    }
}
