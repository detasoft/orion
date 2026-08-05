package pro.deta.orion.git.parser.wire.continuation.v0v1;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.GitNativeClientOutput;
import pro.deta.orion.git.parser.wire.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.wire.advertisement.GitAdvertisedRef;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.git.parser.wire.capability.GitCapability;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestService;
import pro.deta.orion.git.parser.wire.continuation.exchange.LegacyUploadNegotiation;
import pro.deta.orion.git.parser.wire.continuation.exchange.LegacyUploadRequest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UploadResponseContinuationTest {
    @Test
    void writesNakAndPackOnSideBandDataChannel() {
        ByteBuf outbound = fixedOutput();
        List<ByteBuf> sent = new ArrayList<>();
        UploadResponseContinuation continuation = continuation(
                new GitNativeClientOutput(
                        outbound,
                        sent::add),
                Set.of("side-band-64k"),
                List.of(GitCapability.SIDE_BAND_64K));

        ContinuationFlow<ByteBuf> flow = drive(continuation);

        try {
            assertThat(flow)
                    .isInstanceOfSatisfying(
                            ContinuationFlow.Transition.class,
                            transition -> assertThat(transition.next())
                                    .isInstanceOf(
                                            Continuation.CompletedSuccess.class));
            ByteBuf response = Unpooled.wrappedBuffer(
                    sent.toArray(ByteBuf[]::new));
            assertThat(response.readCharSequence(
                    8,
                    StandardCharsets.US_ASCII))
                    .hasToString("0008NAK\n");
            int packetLength = Integer.parseInt(
                    response.readCharSequence(
                            4,
                            StandardCharsets.US_ASCII).toString(),
                    16);
            assertThat(response.readByte()).isEqualTo((byte) 1);
            assertThat(response.readCharSequence(
                    4,
                    StandardCharsets.US_ASCII))
                    .hasToString("PACK");
            response.skipBytes(packetLength - 9);
            assertThat(response.readCharSequence(
                    4,
                    StandardCharsets.US_ASCII))
                    .hasToString("0000");
            response.release();
        } finally {
            outbound.release();
        }
    }

    @Test
    void yieldsWhenSideBandResponseDoesNotFitCurrentOutput() {
        ByteBuf outbound = fixedOutput();
        outbound.writerIndex(outbound.capacity());
        UploadResponseContinuation continuation = continuation(
                new GitNativeClientOutput(outbound, ByteBuf::release),
                Set.of("side-band-64k"),
                List.of(GitCapability.SIDE_BAND_64K));

        ContinuationFlow<ByteBuf> flow =
                continuation.process(Unpooled.EMPTY_BUFFER);

        try {
            int yields = 0;
            while (flow instanceof
                    ContinuationFlow.Yield<ByteBuf> yielded) {
                yields++;
                yielded.task().run();
                flow = continuation.process(
                        Unpooled.EMPTY_BUFFER);
            }
            assertThat(yields).isGreaterThanOrEqualTo(2);
            assertThat(flow)
                    .isInstanceOf(ContinuationFlow.Transition.class);
        } finally {
            outbound.release();
        }
    }

    @Test
    void writesNakAndRawPackWithoutSideBand64k() {
        ByteBuf outbound = fixedOutput();
        List<ByteBuf> sent = new ArrayList<>();
        UploadResponseContinuation continuation = continuation(
                new GitNativeClientOutput(
                        outbound,
                        sent::add),
                Set.of(),
                List.of(GitCapability.SIDE_BAND_64K));

        ContinuationFlow<ByteBuf> flow = drive(continuation);

        try {
            assertThat(flow)
                    .isInstanceOfSatisfying(
                            ContinuationFlow.Transition.class,
                            transition -> assertThat(transition.next())
                                    .isInstanceOf(
                                            Continuation.CompletedSuccess.class));
            ByteBuf response = Unpooled.wrappedBuffer(
                    sent.toArray(ByteBuf[]::new));
            assertThat(response.readCharSequence(
                    8,
                    StandardCharsets.US_ASCII))
                    .hasToString("0008NAK\n");
            assertThat(response.readCharSequence(
                    4,
                    StandardCharsets.US_ASCII))
                    .hasToString("PACK");
            response.release();
        } finally {
            outbound.release();
        }
    }

    private static UploadResponseContinuation continuation(
            GitNativeClientOutput output,
            Set<String> requestedCapabilities,
            List<GitCapability> advertisedCapabilities) {
        InitialRequestData initialRequest = new InitialRequestData(
                InitialRequestService.UPLOAD_PACK,
                "/demo.git",
                "localhost",
                Map.of());
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.create("/demo.git")
                        .valueOrFailure("repository");
        GitObjectId objectId = repository.writeObject(
                ObjectType.BLOB,
                "content".getBytes(StandardCharsets.UTF_8));
        LegacyUploadRequest request = new LegacyUploadRequest(
                initialRequest,
                Set.of(objectId),
                requestedCapabilities,
                new GitV1Advertisement(
                        advertisedCapabilities,
                        List.of(GitAdvertisedRef.direct(
                                objectId.value(),
                                "refs/heads/main"))));
        GitMinimalWireMachine.Context context =
                GitMinimalWireMachine.testContext(
                        UnpooledByteBufAllocator.DEFAULT,
                        output,
                        provider,
                        GitNativeRepositoryAccessHook.ALLOW_ALL);
        return new UploadResponseContinuation(
                context,
                new LegacyUploadNegotiation(request, Set.of()));
    }

    private static ContinuationFlow<ByteBuf> drive(
            UploadResponseContinuation continuation) {
        ContinuationFlow<ByteBuf> flow =
                continuation.process(Unpooled.EMPTY_BUFFER);
        while (flow instanceof
                ContinuationFlow.Yield<ByteBuf> yielded) {
            yielded.task().run();
            flow = continuation.process(
                    Unpooled.EMPTY_BUFFER);
        }
        return flow;
    }

    private static ByteBuf fixedOutput() {
        return Unpooled.buffer(
                GitNativeClientOutput.BUFFER_CAPACITY,
                GitNativeClientOutput.BUFFER_CAPACITY);
    }
}
