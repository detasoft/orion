package pro.deta.orion.git.parser.wire.continuation.v0v1;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.continuation.exchange.LegacyUploadRequest;
import pro.deta.orion.lifecycle.state.TestOnly;

import java.util.Objects;

final class UploadResponseContinuation implements Continuation<ByteBuf> {
    private final GitMinimalWireMachine.Context context;
    private final LegacyUploadRequest request;

    UploadResponseContinuation(
            GitMinimalWireMachine.Context context,
            LegacyUploadRequest request) {
        this.context = Objects.requireNonNull(context, "context");
        this.request = Objects.requireNonNull(request, "request");
    }

    @Override
    public ContinuationFlow<ByteBuf> process(ByteBuf input) {
        return ContinuationFlow.completedError(
                "Legacy upload-pack response is not implemented");
    }

    @TestOnly
    LegacyUploadRequest request() {
        return request;
    }
}
