package pro.deta.orion.git.parser.wire.continuation.v0v1;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestService;
import pro.deta.orion.git.parser.wire.continuation.exchange.LegacyUploadRequest;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UploadNegotiationContinuationTest {
    @Test
    void negotiationPlaceholderFailsWithoutConsumingInput() {
        UploadNegotiationContinuation continuation =
                new UploadNegotiationContinuation(context(), request());

        assertPlaceholder(
                continuation,
                "negotiation is not implemented");
    }

    @Test
    void responsePlaceholderFailsWithoutConsumingInput() {
        UploadResponseContinuation continuation =
                new UploadNegotiationContinuation(context(), request())
                        .responseBoundary();

        assertThat(continuation.request()).isEqualTo(request());
        assertPlaceholder(
                continuation,
                "response is not implemented");
    }

    private static void assertPlaceholder(
            Continuation<ByteBuf> continuation,
            String message) {
        ByteBuf input = Unpooled.wrappedBuffer(new byte[] {1});
        ContinuationFlow<ByteBuf> flow;
        try {
            flow = continuation.process(input);
            assertThat(input.readerIndex()).isZero();
        } finally {
            input.release();
        }

        assertThat(flow)
                .isInstanceOf(ContinuationFlow.Transition.class);
        Continuation<?> next =
                ((ContinuationFlow.Transition<?>) flow).next();
        assertThat(next)
                .isInstanceOfSatisfying(
                        Continuation.CompletedError.class,
                        error -> assertThat(error.message())
                                .contains(message));
    }

    private static GitMinimalWireMachine.Context context() {
        return GitMinimalWireMachine.testContext(
                UnpooledByteBufAllocator.DEFAULT);
    }

    private static LegacyUploadRequest request() {
        return RequestHolder.VALUE;
    }

    private static final class RequestHolder {
        private static final LegacyUploadRequest VALUE =
                new LegacyUploadRequest(
                        new InitialRequestData(
                                InitialRequestService.UPLOAD_PACK,
                                "/demo.git",
                                "localhost",
                                Map.of()),
                        Set.of(GitObjectId.of("1".repeat(40))),
                        Set.of("thin-pack"));
    }
}
