package pro.deta.orion.git.parser.wire.continuation.v0v1;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.parser.wire.continuation.exchange.LegacyReceiveCommandSection;
import pro.deta.orion.git.parser.wire.continuation.exchange.LegacyReceivePack;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.lifecycle.state.TestOnly;

import java.util.Objects;

final class ReceivePackBoundaryContinuation
        implements Continuation<ByteBuf> {
    private final GitMinimalWireMachine.Context context;
    private final LegacyReceiveCommandSection commandSection;

    ReceivePackBoundaryContinuation(
            GitMinimalWireMachine.Context context,
            LegacyReceiveCommandSection commandSection) {
        this.context = Objects.requireNonNull(context, "context");
        this.commandSection = Objects.requireNonNull(
                commandSection,
                "commandSection");
    }

    @Override
    public ContinuationFlow<ByteBuf> process(ByteBuf input) {
        if (commandSection.requiresPack()) {
            return ContinuationFlow.transition(
                    new ReceivePackIngestionContinuation(
                            context,
                            commandSection,
                            context.repositoryService
                                    .beginLegacyReceivePack(
                                            commandSection.initialRequest())));
        }
        return ContinuationFlow.transition(
                ReceivePackIngestionContinuation.completeReceivePack(
                        context,
                        new LegacyReceivePack(
                                commandSection,
                                new LooseObjectStore())));
    }

    @TestOnly
    LegacyReceiveCommandSection commandSection() {
        return commandSection;
    }
}
