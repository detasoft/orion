package pro.deta.orion.git.parser.wire.continuation.v2;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;

public final class UploadPackContinuation implements Continuation<ByteBuf> {
    private final GitMinimalWireMachine.Context context;
    private final InitialRequestData data;

    public UploadPackContinuation(
            GitMinimalWireMachine.Context context,
            InitialRequestData data) {
        this.context = context;
        this.data = data;
    }

    @Override
    public ContinuationFlow<ByteBuf> process(ByteBuf input) {
        throw new IllegalStateException("Not implemented");
    }
}
