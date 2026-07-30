package pro.deta.orion.git.parser.wire.continuation.v0v1;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.nativestorage.pack.PackIngestionResult;
import pro.deta.orion.git.nativestorage.pack.PackIngestionSession;
import pro.deta.orion.git.parser.wire.continuation.exchange.LegacyReceiveCommandSection;
import pro.deta.orion.git.parser.wire.continuation.exchange.LegacyReceivePack;
import pro.deta.orion.lifecycle.state.TestOnly;

import java.util.Objects;

final class ReceivePackIngestionContinuation
        implements Continuation<ByteBuf> {
    private final LegacyReceiveCommandSection commandSection;
    private final PackIngestionSession session;
    private LegacyReceivePack receivePack;

    ReceivePackIngestionContinuation(
            LegacyReceiveCommandSection commandSection,
            PackIngestionSession session) {
        this.commandSection = Objects.requireNonNull(
                commandSection,
                "commandSection");
        this.session = Objects.requireNonNull(session, "session");
    }

    @Override
    public ContinuationFlow<ByteBuf> process(ByteBuf input) {
        try {
            return switch (session.accept(input)) {
                case PackIngestionResult.NeedInput ignored ->
                        ContinuationFlow.await();
                case PackIngestionResult.Complete complete -> {
                    receivePack = new LegacyReceivePack(
                            commandSection,
                            complete.quarantine());
                    yield ContinuationFlow.transition(
                            Continuation.completedSuccess(this));
                }
                case PackIngestionResult.Failed failed ->
                        ContinuationFlow.transition(
                                Continuation.completedError(
                                        "Failed to ingest native Git receive pack",
                                        failed.failure()));
            };
        } catch (RuntimeException error) {
            return ContinuationFlow.transition(
                    Continuation.completedError(
                            "Failed to ingest native Git receive pack",
                            error));
        }
    }

    @Override
    public void close() {
        session.close();
    }

    @TestOnly
    LegacyReceivePack receivePack() {
        if (receivePack == null) {
            throw new IllegalStateException(
                    "Receive pack is not complete");
        }
        return receivePack;
    }
}
