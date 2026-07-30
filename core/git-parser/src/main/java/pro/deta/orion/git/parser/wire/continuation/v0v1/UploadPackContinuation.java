package pro.deta.orion.git.parser.wire.continuation.v0v1;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.GitNativeClientOutput;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;

import java.util.Objects;

public final class UploadPackContinuation implements Continuation<ByteBuf> {
    private final GitMinimalWireMachine.Context context;
    private final InitialRequestData data;
    private final GitV1Advertisement advertisement;

    public UploadPackContinuation(
            GitMinimalWireMachine.Context context,
            InitialRequestData data,
            GitV1Advertisement advertisement) {
        this.context = Objects.requireNonNull(context, "context");
        this.data = Objects.requireNonNull(data, "data");
        this.advertisement = Objects.requireNonNull(
                advertisement,
                "advertisement");
    }

    @Override
    public ContinuationFlow<ByteBuf> process(ByteBuf input) {
        try {
            GitNativeClientOutput.SendResult result =
                    context.clientOutput.sendAdvertisement(
                            advertisement);
            return result.transitionTo(
                    new UploadRequestContinuation(context, data));
        } catch (RuntimeException error) {
            return ContinuationFlow.completedError(
                    "Failed to advertise native Git repository",
                    error);
        }
    }
}
