package pro.deta.orion.git.parser.wire.continuation.v0v1;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;

import java.util.Objects;

import static pro.deta.orion.git.parser.wire.continuation.OutputTransitions.transitionAfterOutput;

public final class ReceivePackContinuation implements Continuation<ByteBuf> {
    private final GitMinimalWireMachine.Context context;
    private final InitialRequestData data;
    private final GitV1Advertisement advertisement;

    public ReceivePackContinuation(
            GitMinimalWireMachine.Context context,
            InitialRequestData data,
            GitV1Advertisement advertisement) {
        this.context = Objects.requireNonNull(context, "context");
        this.data = Objects.requireNonNull(data, "data");
        this.advertisement = Objects.requireNonNull(
                advertisement,
                "advertisement");
    }

    public static Continuation<ByteBuf> afterAdvertisement(
            GitMinimalWireMachine.Context context,
            InitialRequestData data,
            GitV1Advertisement advertisement) {
        return new ReceiveCommandContinuation(
                context,
                data,
                advertisement);
    }

    @Override
    public ContinuationFlow<ByteBuf> process(ByteBuf input) {
        return transitionAfterOutput(
                () -> context.clientOutput.sendAdvertisement(advertisement),
                new ReceiveCommandContinuation(
                        context,
                        data,
                        advertisement),
                "Failed to advertise native Git repository for receive-pack");
    }
}
