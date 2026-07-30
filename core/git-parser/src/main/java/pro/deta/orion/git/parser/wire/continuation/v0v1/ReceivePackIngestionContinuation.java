package pro.deta.orion.git.parser.wire.continuation.v0v1;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.nativestorage.pack.PackIngestionResult;
import pro.deta.orion.git.nativestorage.pack.PackIngestionSession;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.GitNativeClientOutput;
import pro.deta.orion.git.parser.wire.GitNativeRepositoryService;
import pro.deta.orion.git.parser.wire.continuation.exchange.LegacyReceiveCommandSection;
import pro.deta.orion.git.parser.wire.continuation.exchange.LegacyReceivePack;
import pro.deta.orion.lifecycle.state.TestOnly;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class ReceivePackIngestionContinuation
        implements Continuation<ByteBuf> {
    private final GitMinimalWireMachine.Context context;
    private final LegacyReceiveCommandSection commandSection;
    private final PackIngestionSession session;
    private LegacyReceivePack receivePack;

    ReceivePackIngestionContinuation(
            GitMinimalWireMachine.Context context,
            LegacyReceiveCommandSection commandSection,
            PackIngestionSession session) {
        this.context = Objects.requireNonNull(context, "context");
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
                            completeReceivePack(context, receivePack));
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

    static Continuation<ByteBuf> completeReceivePack(
            GitMinimalWireMachine.Context context,
            LegacyReceivePack receivePack) {
        try {
            List<GitNativeClientOutput.ReceiveCommandStatus> outputStatuses =
                    new ArrayList<>();
            for (GitNativeRepositoryService.ReceivePackStatus status
                    : context.repositoryService
                            .completeLegacyReceivePack(receivePack)) {
                outputStatuses.add(
                        new GitNativeClientOutput.ReceiveCommandStatus(
                                status.refName(),
                                status.ok(),
                                status.message()));
            }
            GitNativeClientOutput.SendResult result =
                    context.clientOutput.sendLegacyReceivePackStatus(
                            outputStatuses,
                            receivePack.commandSection()
                                    .capabilities()
                                    .contains("side-band-64k"));
            return input -> result.transitionTo(
                    Continuation.completedSuccess(
                            new CompletedReceivePackContinuation(
                                    receivePack)));
        } catch (RuntimeException error) {
            return Continuation.completedError(
                    "Failed to complete native Git receive pack",
                    error);
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

    private record CompletedReceivePackContinuation(
            LegacyReceivePack receivePack) implements Continuation<ByteBuf> {
        private CompletedReceivePackContinuation {
            Objects.requireNonNull(receivePack, "receivePack");
        }

        @Override
        public ContinuationFlow<ByteBuf> process(ByteBuf input) {
            return ContinuationFlow.await();
        }
    }
}
