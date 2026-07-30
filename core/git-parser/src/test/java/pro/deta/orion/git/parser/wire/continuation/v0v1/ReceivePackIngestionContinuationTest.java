package pro.deta.orion.git.parser.wire.continuation.v0v1;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.pack.PackIngestionResult;
import pro.deta.orion.git.nativestorage.pack.PackIngestionSession;
import pro.deta.orion.git.parser.wire.advertisement.GitAdvertisedRef;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestService;
import pro.deta.orion.git.parser.wire.continuation.exchange.LegacyReceiveCommand;
import pro.deta.orion.git.parser.wire.continuation.exchange.LegacyReceiveCommandSection;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReceivePackIngestionContinuationTest {
    private static final String NULL_ID = "0".repeat(40);
    private static final String NEW_ID = "1".repeat(40);

    @Test
    void forwardsOriginalFragmentsUntilCheckedQuarantineCompletes() {
        RecordingSession session = new RecordingSession();
        LegacyReceiveCommandSection section = section();
        ReceivePackIngestionContinuation continuation =
                new ReceivePackIngestionContinuation(section, session);
        ByteBuf first = Unpooled.wrappedBuffer(new byte[]{1});
        ByteBuf second = Unpooled.wrappedBuffer(new byte[]{2});
        try {
            ContinuationFlow<ByteBuf> firstFlow =
                    continuation.process(first);
            ContinuationFlow<ByteBuf> secondFlow =
                    continuation.process(second);

            assertThat(firstFlow)
                    .isInstanceOf(ContinuationFlow.Await.class);
            assertThat(secondFlow)
                    .isInstanceOfSatisfying(
                            ContinuationFlow.Transition.class,
                            transition -> assertThat(transition.next())
                                    .isInstanceOf(
                                            Continuation.CompletedSuccess.class));
            assertThat(session.inputs).containsExactly(first, second);
            assertThat(continuation.receivePack().commandSection())
                    .isSameAs(section);
            assertThat(continuation.receivePack().quarantine())
                    .isSameAs(session.quarantine);
        } finally {
            first.release();
            second.release();
        }
    }

    @Test
    void closesRepositorySessionOnContinuationClose() {
        RecordingSession session = new RecordingSession();
        ReceivePackIngestionContinuation continuation =
                new ReceivePackIngestionContinuation(section(), session);

        continuation.close();

        assertThat(session.closed).isTrue();
    }

    private static LegacyReceiveCommandSection section() {
        InitialRequestData request = new InitialRequestData(
                InitialRequestService.RECEIVE_PACK,
                "/demo.git",
                "localhost",
                Map.of());
        GitV1Advertisement advertisement = new GitV1Advertisement(
                List.of(),
                List.of(GitAdvertisedRef.direct(
                        NULL_ID,
                        "capabilities^{}")));
        return new LegacyReceiveCommandSection(
                request,
                List.of(new LegacyReceiveCommand(
                        GitObjectId.of(NULL_ID),
                        GitObjectId.of(NEW_ID),
                        "refs/heads/main")),
                Set.of("report-status"),
                advertisement);
    }

    private static final class RecordingSession
            implements PackIngestionSession {
        private final List<ByteBuf> inputs =
                new java.util.ArrayList<>();
        private final LooseObjectStore quarantine =
                new LooseObjectStore();
        private boolean closed;

        @Override
        public PackIngestionResult accept(ByteBuf input) {
            inputs.add(input);
            if (inputs.size() == 1) {
                return new PackIngestionResult.NeedInput();
            }
            return new PackIngestionResult.Complete(quarantine);
        }

        @Override
        public PackIngestionResult endOfInput() {
            return new PackIngestionResult.Failed(
                    new pro.deta.orion.git.nativestorage.pack.PackParseException(
                            "incomplete"));
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
