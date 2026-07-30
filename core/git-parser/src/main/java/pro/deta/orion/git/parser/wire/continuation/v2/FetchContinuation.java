package pro.deta.orion.git.parser.wire.continuation.v2;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;

import java.util.Objects;

final class FetchContinuation implements Continuation<ByteBuf> {
    @SuppressWarnings("unused")
    private final GitMinimalWireMachine.Context context;
    @SuppressWarnings("unused")
    private final InitialRequestData data;

    FetchContinuation(
            GitMinimalWireMachine.Context context,
            InitialRequestData data) {
        this.context = Objects.requireNonNull(context, "context");
        this.data = Objects.requireNonNull(data, "data");
    }

    @Override
    public ContinuationFlow<ByteBuf> process(ByteBuf input) {
        return ContinuationFlow.completedError(
                "Protocol v2 fetch response is not implemented");
    }

}
