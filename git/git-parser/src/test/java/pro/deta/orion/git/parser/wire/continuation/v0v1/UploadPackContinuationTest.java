package pro.deta.orion.git.parser.wire.continuation.v0v1;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.GitNativeClientOutput;
import pro.deta.orion.git.parser.wire.RecordingBufferedByteOutput;
import pro.deta.orion.git.parser.wire.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.wire.SubmittedByteBufOutput;
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
                    new GitNativeClientOutput(new RecordingBufferedByteOutput(outbound)));

            ContinuationFlow<ByteBuf> flow =
                    continuation.process(input);

            assertThat(flow)
                    .isInstanceOfSatisfying(
                            ContinuationFlow.Transition.class,
                            transition -> assertAdvertisement(
                                    transition.next(),
                                    advertisement()));
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
                new SubmittedByteBufOutput(outbound, ByteBuf::release));
        try {
            UploadPackContinuation continuation =
                    continuation(output);

            ContinuationFlow<ByteBuf> flow = continuation.process(input);

            assertThat(flow)
                    .isInstanceOfSatisfying(
                            ContinuationFlow.Transition.class,
                            resumed -> assertAdvertisement(
                                    resumed.next(),
                                    advertisement()));
            assertThat(input.readerIndex()).isZero();
        } finally {
            input.release();
            outbound.release();
        }
    }

    @Test
    void completesWithErrorWhenAdvertisementOutputRejectsOperation() {
        ByteBuf outbound = outputBuffer();
        ByteBuf input = Unpooled.wrappedBuffer(new byte[] {1});
        GitNativeClientOutput output = new GitNativeClientOutput(
                new SubmittedByteBufOutput(outbound, ignored -> {
                    throw new IllegalStateException("delivery failed");
                }));

        try {
            ContinuationFlow<ByteBuf> flow =
                    continuation(output).process(input);

            assertThat(flow)
                    .isInstanceOf(ContinuationFlow.Transition.class);
            assertThat(((ContinuationFlow.Transition<?>) flow).next())
                    .isInstanceOfSatisfying(
                            Continuation.CompletedError.class,
                            error -> {
                                assertThat(error.message()).isEqualTo(
                                        "Failed to advertise native Git repository");
                                assertThat(error.throwable())
                                        .hasMessage(
                                                "delivery failed");
                            });
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
                        output,
                        new InMemoryNativeGitRepositoryProvider(),
                        GitNativeRepositoryAccessHook.ALLOW_ALL);
        InitialRequestData data = new InitialRequestData(
                InitialRequestService.UPLOAD_PACK,
                "/demo.git",
                "localhost",
                Map.of());
        return new UploadPackContinuation(
                context,
                data,
                advertisement());
    }

    private static GitV1Advertisement advertisement() {
        return AdvertisementHolder.VALUE;
    }

    private static void assertAdvertisement(
            Continuation<?> continuation,
            GitV1Advertisement advertisement) {
        assertThat(continuation)
                .isInstanceOf(UploadRequestContinuation.class);
        UploadRequestContinuation request =
                (UploadRequestContinuation) continuation;
        ByteBuf input = Unpooled.buffer();
        writeData(input, "want " + MAIN_ID + "\n");
        input.writeCharSequence(
                "0000",
                java.nio.charset.StandardCharsets.US_ASCII);
        try {
            Continuation<ByteBuf> current = request;
            while (!(current instanceof UploadNegotiationContinuation)) {
                ContinuationFlow<ByteBuf> flow = current.process(input);
                assertThat(flow)
                        .isInstanceOf(ContinuationFlow.Transition.class);
                current = ((ContinuationFlow.Transition<ByteBuf>) flow).next();
            }
            assertThat(((UploadNegotiationContinuation) current)
                    .request()
                    .serverAdvertisement())
                    .isSameAs(advertisement);
        } finally {
            input.release();
        }
    }

    private static void writeData(ByteBuf output, String value) {
        byte[] payload = value.getBytes(
                java.nio.charset.StandardCharsets.US_ASCII);
        output.writeCharSequence(
                "%04x".formatted(payload.length + 4),
                java.nio.charset.StandardCharsets.US_ASCII);
        output.writeBytes(payload);
    }

    private static ByteBuf outputBuffer() {
        return Unpooled.buffer(
                GitNativeClientOutput.BUFFER_CAPACITY,
                GitNativeClientOutput.BUFFER_CAPACITY);
    }

    private static final class AdvertisementHolder {
        private static final GitV1Advertisement VALUE =
                new GitV1Advertisement(
                        List.of(),
                        List.of(GitAdvertisedRef.direct(
                                MAIN_ID,
                                "refs/heads/main")));
    }
}
