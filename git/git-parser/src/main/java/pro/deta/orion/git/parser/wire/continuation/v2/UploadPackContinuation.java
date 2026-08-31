package pro.deta.orion.git.parser.wire.continuation.v2;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;

import java.util.Objects;

import static pro.deta.orion.git.parser.wire.continuation.OutputTransitions.transitionAfterOutput;

public final class UploadPackContinuation implements Continuation<ByteBuf> {
    private final GitMinimalWireMachine.Context context;
    private final InitialRequestData data;

    public UploadPackContinuation(
            GitMinimalWireMachine.Context context,
            InitialRequestData data) {
        this.context = Objects.requireNonNull(context, "context");
        this.data = Objects.requireNonNull(data, "data");
    }

    public static Continuation<ByteBuf> afterAdvertisement(
            GitMinimalWireMachine.Context context,
            InitialRequestData data) {
        return new UploadCommandContinuation(context, data);
    }

    @Override
    public ContinuationFlow<ByteBuf> process(ByteBuf input) {
        return transitionAfterOutput(
                () -> context.clientOutput.sendV2UploadPackAdvertisement(
                        context.configuration.protocolV2()),
                new UploadCommandContinuation(context, data),
                "Failed to advertise protocol v2 upload-pack capabilities");
    }
}
