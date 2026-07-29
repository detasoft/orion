package pro.deta.orion.git.parser.wire.continuation;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.lifecycle.state.TestOnly;

public class StructuredPayloadContinuation implements Continuation<ByteBuf> {
    private final InitialRequestData data;

    public StructuredPayloadContinuation(
            GitMinimalWireMachine.Context context,
            InitialRequestData data) {
        this.data = data;
    }

    @Override
    public ContinuationFlow<ByteBuf> process(ByteBuf input) {
        throw new IllegalStateException("Not implemented");
    }

    @TestOnly
    InitialRequestData initialRequestData() {
        return data;
    }
}
