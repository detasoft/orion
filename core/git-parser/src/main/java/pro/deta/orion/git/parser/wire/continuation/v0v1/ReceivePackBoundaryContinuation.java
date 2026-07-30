package pro.deta.orion.git.parser.wire.continuation.v0v1;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.parser.wire.continuation.exchange.LegacyReceiveCommandSection;
import pro.deta.orion.lifecycle.state.TestOnly;

import java.util.Objects;

final class ReceivePackBoundaryContinuation
        implements Continuation<ByteBuf> {
    private final LegacyReceiveCommandSection commandSection;

    ReceivePackBoundaryContinuation(
            LegacyReceiveCommandSection commandSection) {
        this.commandSection = Objects.requireNonNull(
                commandSection,
                "commandSection");
    }

    @Override
    public ContinuationFlow<ByteBuf> process(ByteBuf input) {
        return ContinuationFlow.transition(
                Continuation.completedSuccess(this));
    }

    @TestOnly
    LegacyReceiveCommandSection commandSection() {
        return commandSection;
    }
}
