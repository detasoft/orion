package pro.deta.orion.git.parser.wire.continuation;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.GitNativeClientOutput;
import pro.deta.orion.git.parser.wire.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.wire.RecordingBufferedByteOutput;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestService;
import pro.deta.orion.git.parser.wire.continuation.v0v1.ReceivePackContinuation;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InitialRequestDispatchContinuationTest extends ByteBufContinuationTest {
    @Test
    void dispatchesLegacyUploadPackWithoutVersion() {
        assertThat(dispatch(InitialRequestService.UPLOAD_PACK, Map.of()))
                .isInstanceOf(
                        pro.deta.orion.git.parser.wire.continuation.v0v1
                                .UploadPackContinuation.class);
    }

    @Test
    void dispatchesLegacyUploadPackVersionOne() {
        assertThat(dispatch(InitialRequestService.UPLOAD_PACK, Map.of("version", "1")))
                .isInstanceOf(
                        pro.deta.orion.git.parser.wire.continuation.v0v1
                                .UploadPackContinuation.class);
    }

    @Test
    void dispatchesUploadPackVersionTwo() {
        assertThat(dispatch(InitialRequestService.UPLOAD_PACK, Map.of("version", "2")))
                .isInstanceOf(
                        pro.deta.orion.git.parser.wire.continuation.v2
                                .UploadPackContinuation.class);
    }

    @Test
    void dispatchesLegacyReceivePackWithoutVersion() {
        assertThat(dispatch(InitialRequestService.RECEIVE_PACK, Map.of()))
                .isInstanceOf(ReceivePackContinuation.class);
    }

    @Test
    void dispatchesLegacyReceivePackVersionOne() {
        assertThat(dispatch(InitialRequestService.RECEIVE_PACK, Map.of("version", "1")))
                .isInstanceOf(ReceivePackContinuation.class);
    }

    @Test
    void rejectsReceivePackVersionTwo() {
        assertUnsupportedVersion(InitialRequestService.RECEIVE_PACK, "2");
    }

    @Test
    void rejectsEmptyVersion() {
        assertUnsupportedVersion(InitialRequestService.UPLOAD_PACK, "");
    }

    @Test
    void rejectsVersionZero() {
        assertUnsupportedVersion(InitialRequestService.UPLOAD_PACK, "0");
    }

    @Test
    void rejectsUnknownVersion() {
        assertUnsupportedVersion(InitialRequestService.UPLOAD_PACK, "3");
    }

    @Test
    void leavesUnreadInputUnchanged() {
        ByteBuf input = Unpooled.copiedBuffer("0000tail", StandardCharsets.US_ASCII);
        int readerIndex = input.readerIndex();
        String unread = input.toString(StandardCharsets.US_ASCII);

        try {
            ContinuationFlow<ByteBuf> flow = new InitialRequestDispatchContinuation(
                    context(),
                    data(InitialRequestService.UPLOAD_PACK, Map.of()))
                    .process(input);

            assertThat(transitionedTo(flow))
                    .isInstanceOf(
                            pro.deta.orion.git.parser.wire.continuation.v0v1
                                    .UploadPackContinuation.class);
            assertThat(input.readerIndex()).isEqualTo(readerIndex);
            assertThat(input.toString(StandardCharsets.US_ASCII)).isEqualTo(unread);
        } finally {
            input.release();
        }
    }

    private static Continuation<ByteBuf> dispatch(
            InitialRequestService service,
            Map<String, String> parameters) {
        ContinuationFlow<ByteBuf> flow = process(
                new InitialRequestDispatchContinuation(context(), data(service, parameters)),
                Unpooled.buffer());
        return transitionedTo(flow);
    }

    protected static GitMinimalWireMachine.Context context() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        provider.create("/project.git").valueOrFailure("repository");
        return GitMinimalWireMachine.testContext(
                new GitNativeClientOutput(new RecordingBufferedByteOutput()),
                provider,
                GitNativeRepositoryAccessHook.ALLOW_ALL);
    }

    private static void assertUnsupportedVersion(
            InitialRequestService service,
            String version) {
        ContinuationFlow<ByteBuf> flow = process(
                new InitialRequestDispatchContinuation(
                        context(),
                        data(service, Map.of("version", version))),
                Unpooled.buffer());

        assertThat(transitionedTo(flow))
                .isInstanceOfSatisfying(
                        Continuation.CompletedError.class,
                        error -> assertThat(error.throwable())
                                .hasMessageContaining(version)
                                .hasMessageContaining(service.name()));
    }

    private static InitialRequestData data(
            InitialRequestService service,
            Map<String, String> parameters) {
        return new InitialRequestData(
                service,
                "/project.git",
                "git.example.com",
                parameters);
    }
}
