package pro.deta.orion.git.parser.wire.continuation;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestService;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class InitialRequestPayloadContinuationTest {
    @Test
    void parsesInitialRequestOneByteAtATime() {
        ByteBuf request = Unpooled.copiedBuffer(
                "git-upload-pack /modèle.git\0"
                        + "host=git.example.com:9418\0"
                        + "\0version=2\0agent=git/2.50\0",
                StandardCharsets.UTF_8);
        try {
            InitialRequestPayloadContinuation continuation =
                    continuation(request.readableBytes());

            InitialRequestData data =
                    completedData(processOneByteAtATime(continuation, request));

            assertThat(data.getService())
                    .isEqualTo(InitialRequestService.UPLOAD_PACK);
            assertThat(data.getRepositoryPath()).isEqualTo("/modèle.git");
            assertThat(data.getHost()).isEqualTo("git.example.com:9418");
            assertThat(data.getParameters())
                    .containsEntry("version", "2")
                    .containsEntry("agent", "git/2.50");
            assertThat(data.getParameters()).isUnmodifiable();
        } finally {
            request.release();
        }
    }

    @Test
    void consumesOnlyDeclaredPayloadAndLeavesFollowingPacketUnread() {
        byte[] payload = bytes("git-receive-pack /team/project.git\0");
        ByteBuf input = Unpooled.buffer();
        input.writeBytes(payload);
        input.writeCharSequence("0000", StandardCharsets.US_ASCII);

        ContinuationFlow<ByteBuf> flow;
        try {
            flow = continuation(payload.length).process(input);
            assertThat(input.toString(StandardCharsets.US_ASCII)).isEqualTo("0000");
        } finally {
            input.release();
        }

        InitialRequestData data =
                completedData(flow);
        assertThat(data.getService())
                .isEqualTo(InitialRequestService.RECEIVE_PACK);
        assertThat(data.getRepositoryPath()).isEqualTo("/team/project.git");
        assertThat(data.getHost()).isNull();
        assertThat(data.getParameters()).isEmpty();
    }

    @Test
    void rejectsUnsupportedService() {
        byte[] payload = bytes("git-upload-archive /project.git\0");

        ContinuationFlow<ByteBuf> flow = process(
                continuation(payload.length),
                Unpooled.wrappedBuffer(payload));

        assertCompletedError(flow, "Unsupported Git service");
    }

    @Test
    void rejectsPayloadEndingInsideParameterValue() {
        byte[] payload = bytes(
                "git-upload-pack /project.git\0"
                        + "host=git.example.com\0"
                        + "\0version=2");

        ContinuationFlow<ByteBuf> flow = process(
                continuation(payload.length),
                Unpooled.wrappedBuffer(payload));

        assertCompletedError(flow, "ends inside a field");
    }

    private static InitialRequestPayloadContinuation continuation(int payloadLength) {
        return new InitialRequestPayloadContinuation(
                GitMinimalWireMachine.testContext(
                        UnpooledByteBufAllocator.DEFAULT),
                payloadLength);
    }

    private static ContinuationFlow<ByteBuf> processOneByteAtATime(
            InitialRequestPayloadContinuation continuation,
            ByteBuf request) {
        ContinuationFlow<ByteBuf> flow = null;
        while (request.isReadable()) {
            boolean lastByte = request.readableBytes() == 1;
            flow = process(
                    continuation,
                    request.readRetainedSlice(1));
            if (!lastByte) {
                assertThat(flow).isInstanceOf(ContinuationFlow.Await.class);
            }
        }
        return flow;
    }

    private static ContinuationFlow<ByteBuf> process(
            InitialRequestPayloadContinuation continuation,
            ByteBuf input) {
        try {
            return continuation.process(input);
        } finally {
            input.release();
        }
    }

    private static InitialRequestData completedData(
            ContinuationFlow<ByteBuf> flow) {
        assertThat(flow).isInstanceOf(ContinuationFlow.Transition.class);
        Continuation<ByteBuf> next =
                ((ContinuationFlow.Transition<ByteBuf>) flow).next();
        assertThat(next).isInstanceOf(StructuredPayloadContinuation.class);
        return ((StructuredPayloadContinuation) next).initialRequestData();
    }

    private static void assertCompletedError(
            ContinuationFlow<ByteBuf> flow,
            String message) {
        assertThat(flow).isInstanceOf(ContinuationFlow.Transition.class);
        Continuation<ByteBuf> next =
                ((ContinuationFlow.Transition<ByteBuf>) flow).next();
        assertThat(next)
                .isInstanceOfSatisfying(
                        Continuation.CompletedError.class,
                        error -> assertThat(error.throwable())
                                .hasMessageContaining(message));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
