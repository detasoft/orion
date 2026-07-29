package pro.deta.orion.git.parser.wire.continuation.v0v1;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.GitNativeClientOutput;
import pro.deta.orion.git.parser.wire.advertisement.GitAdvertisedRef;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestService;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UploadPackContinuationTest {
    private static final String MAIN_ID = "1".repeat(40);

    @Test
    void transitionsAfterAdvertisementFitsOutput() {
        ByteBuf outbound = outputBuffer();
        ByteBuf input = Unpooled.wrappedBuffer(new byte[] {1});
        try {
            UploadPackContinuation continuation = continuation(
                    new GitNativeClientOutput(outbound));

            ContinuationFlow<ByteBuf> flow =
                    continuation.process(input);

            assertThat(flow)
                    .isInstanceOfSatisfying(
                            ContinuationFlow.Transition.class,
                            transition -> assertThat(transition.next())
                                    .isInstanceOf(
                                            UploadRequestContinuation.class));
            assertThat(input.readerIndex()).isZero();
        } finally {
            input.release();
            outbound.release();
        }
    }

    @Test
    void transitionsAndYieldsRealStreamingTask() {
        ByteBuf outbound = outputBuffer();
        outbound.writerIndex(outbound.capacity() - 1);
        ByteBuf input = Unpooled.wrappedBuffer(new byte[] {1});
        GitNativeClientOutput output = new GitNativeClientOutput(
                outbound,
                ByteBuf::release);
        try {
            UploadPackContinuation continuation =
                    continuation(output);

            ContinuationFlow.TransitionAndYield<ByteBuf> flow =
                    (ContinuationFlow.TransitionAndYield<ByteBuf>)
                            continuation.process(input);
            flow.task().run();

            assertThat(flow.next())
                    .isInstanceOf(UploadRequestContinuation.class);
            assertThat(input.readerIndex()).isZero();
        } finally {
            input.release();
            outbound.release();
        }
    }

    private static UploadPackContinuation continuation(
            GitNativeClientOutput output) {
        GitMinimalWireMachine.Context context =
                GitMinimalWireMachine.testContext(
                        UnpooledByteBufAllocator.DEFAULT,
                        output);
        InitialRequestData data = new InitialRequestData(
                InitialRequestService.UPLOAD_PACK,
                "/demo.git",
                "localhost",
                Map.of());
        GitV1Advertisement advertisement =
                new GitV1Advertisement(
                        List.of(),
                        List.of(GitAdvertisedRef.direct(
                                MAIN_ID,
                                "refs/heads/main")));
        return new UploadPackContinuation(
                context,
                data,
                advertisement);
    }

    private static ByteBuf outputBuffer() {
        return Unpooled.buffer(
                GitNativeClientOutput.BUFFER_CAPACITY,
                GitNativeClientOutput.BUFFER_CAPACITY);
    }
}
