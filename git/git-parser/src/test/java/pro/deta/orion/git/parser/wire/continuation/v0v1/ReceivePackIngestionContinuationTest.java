package pro.deta.orion.git.parser.wire.continuation.v0v1;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.pack.PackIngestionResult;
import pro.deta.orion.git.nativestorage.pack.PackIngestionSession;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.GitNativeClientOutput;
import pro.deta.orion.git.parser.wire.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.wire.GitWireConfiguration;
import pro.deta.orion.git.parser.wire.advertisement.GitAdvertisedRef;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestService;
import pro.deta.orion.git.parser.wire.continuation.exchange.LegacyReceiveCommand;
import pro.deta.orion.git.parser.wire.continuation.exchange.LegacyReceiveCommandSection;
import pro.deta.orion.git.parser.wire.continuation.exchange.LegacyReceivePack;

import java.nio.charset.StandardCharsets;
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
                new ReceivePackIngestionContinuation(
                        defaultContext(),
                        section,
                        session);
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
                    .isInstanceOf(ContinuationFlow.Transition.class);
            Continuation<ByteBuf> completion =
                    ((ContinuationFlow.Transition<ByteBuf>) secondFlow)
                            .next();
            assertThat(completion.process(Unpooled.EMPTY_BUFFER))
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
                new ReceivePackIngestionContinuation(
                        defaultContext(),
                        section(),
                        session);

        continuation.close();

        assertThat(session.closed).isTrue();
    }

    @Test
    void disabledReportStatusDoesNotSendRequestedStatusResponse() {
        GitWireConfiguration configuration =
                receiveConfiguration(false, true);
        ByteBuf outbound = outputBuffer();
        try {
            ContinuationFlow<ByteBuf> flow = completeReceivePack(
                    outbound,
                    configuration,
                    Set.of("report-status", "side-band-64k"));

            assertThat(flow)
                    .isInstanceOf(ContinuationFlow.Transition.class);
            assertThat(outbound.isReadable()).isFalse();
        } finally {
            outbound.release();
        }
    }

    @Test
    void disabledSideBandSendsRequestedReportStatusWithoutSideBand() {
        GitWireConfiguration configuration =
                receiveConfiguration(true, false);
        ByteBuf outbound = outputBuffer();
        try {
            ContinuationFlow<ByteBuf> flow = completeReceivePack(
                    outbound,
                    configuration,
                    Set.of("report-status", "side-band-64k"));

            assertThat(flow)
                    .isInstanceOf(ContinuationFlow.Transition.class);
            assertThat(outbound.toString(StandardCharsets.US_ASCII))
                    .isEqualTo(
                            "000eunpack ok\n"
                                    + "0017ok refs/heads/main\n"
                                    + "0000");
        } finally {
            outbound.release();
        }
    }

    private static LegacyReceiveCommandSection section() {
        return section(Set.of("report-status"));
    }

    private static LegacyReceiveCommandSection section(
            Set<String> capabilities) {
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
                capabilities,
                advertisement);
    }

    private static ContinuationFlow<ByteBuf> completeReceivePack(
            ByteBuf outbound,
            GitWireConfiguration configuration,
            Set<String> capabilities) {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        GitNativeClientOutput output =
                new GitNativeClientOutput(outbound);
        GitMinimalWireMachine.Context context =
                GitMinimalWireMachine.testContext(
                        UnpooledByteBufAllocator.DEFAULT,
                        output,
                        provider,
                        GitNativeRepositoryAccessHook.ALLOW_ALL,
                        configuration);
        Continuation<ByteBuf> continuation =
                ReceivePackIngestionContinuation.completeReceivePack(
                        context,
                        new LegacyReceivePack(
                                section(capabilities),
                                new LooseObjectStore()));
        return continuation.process(Unpooled.EMPTY_BUFFER);
    }

    private static GitMinimalWireMachine.Context defaultContext() {
        ByteBuf outbound = outputBuffer();
        return GitMinimalWireMachine.testContext(
                UnpooledByteBufAllocator.DEFAULT,
                new GitNativeClientOutput(outbound),
                new InMemoryNativeGitRepositoryProvider(),
                GitNativeRepositoryAccessHook.ALLOW_ALL);
    }

    private static ByteBuf outputBuffer() {
        return Unpooled.buffer(
                GitNativeClientOutput.BUFFER_CAPACITY,
                GitNativeClientOutput.BUFFER_CAPACITY);
    }

    private static GitWireConfiguration receiveConfiguration(
            boolean reportStatus,
            boolean sideBand64k) {
        GitWireConfiguration supported =
                GitWireConfiguration.allSupported();
        return new GitWireConfiguration(
                supported.uploadPack(),
                new GitWireConfiguration.LegacyReceivePack(
                        reportStatus,
                        sideBand64k,
                        true,
                        true,
                        true),
                supported.protocolV2());
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
