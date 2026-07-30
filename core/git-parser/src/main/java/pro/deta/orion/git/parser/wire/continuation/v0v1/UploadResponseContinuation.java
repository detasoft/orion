package pro.deta.orion.git.parser.wire.continuation.v0v1;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.nativestorage.upload.NativeFetchRequest;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.continuation.exchange.LegacyUploadNegotiation;

import java.util.Objects;

final class UploadResponseContinuation implements Continuation<ByteBuf> {
    private final GitMinimalWireMachine.Context context;
    private final NativeFetchRequest fetchRequest;

    UploadResponseContinuation(
            GitMinimalWireMachine.Context context,
            LegacyUploadNegotiation negotiation) {
        this.context = Objects.requireNonNull(context, "context");
        this.fetchRequest = Objects.requireNonNull(
                negotiation,
                "negotiation").nativeFetchRequest();
    }

    @Override
    public ContinuationFlow<ByteBuf> process(ByteBuf input) {
        return ContinuationFlow.completedError(
                "Legacy upload-pack response is not implemented");
    }
}
