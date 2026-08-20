package pro.deta.orion.git.parser.wire.continuation;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.parser.wire.ProtocolStage;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ControlHeaderContinuationTest extends ByteBufContinuationTest {
    @Test
    void readsDataHeaderOneByteAtATime() {
        ByteBuf header = Unpooled.copiedBuffer(
                "0005",
                StandardCharsets.US_ASCII);
        try {
            assertInitialRequestPayload(
                    processOneByteAtATime(continuation(), header));
        } finally {
            header.release();
        }
    }

    @Test
    void consumesOnlyHeaderAndLeavesFollowingBytesUnread() {
        ByteBuf input = Unpooled.copiedBuffer(
                "0005x0000",
                StandardCharsets.US_ASCII);

        ContinuationFlow<ByteBuf> flow;
        try {
            flow = continuation().process(input);
            assertThat(input.toString(StandardCharsets.US_ASCII))
                    .isEqualTo("x0000");
        } finally {
            input.release();
        }

        assertInitialRequestPayload(flow);
    }

    @Test
    void rejectsFlushForInitialRequest() {
        ContinuationFlow<ByteBuf> flow =
                processHeaderOneByteAtATime("0000");

        assertCompletedError(
                flow,
                "FLUSH is not supported for stage INITIAL_REQUEST");
    }

    @Test
    void rejectsDelimiterForInitialRequest() {
        ContinuationFlow<ByteBuf> flow =
                processHeaderOneByteAtATime("0001");

        assertCompletedError(
                flow,
                "DELIMITER is not supported for stage INITIAL_REQUEST");
    }

    @Test
    void rejectsResponseEndForInitialRequest() {
        ContinuationFlow<ByteBuf> flow =
                processHeaderOneByteAtATime("0002");

        assertCompletedError(
                flow,
                "RESPONSE_END is not supported for stage INITIAL_REQUEST");
    }

    private static ControlHeaderContinuation continuation() {
        return new ControlHeaderContinuation(
                context(),
                ProtocolStage.INITIAL_REQUEST);
    }

    private static ContinuationFlow<ByteBuf> processHeaderOneByteAtATime(
            String value) {
        ByteBuf header = header(value);
        try {
            return processOneByteAtATime(continuation(), header);
        } finally {
            header.release();
        }
    }

    private static void assertInitialRequestPayload(
            ContinuationFlow<ByteBuf> flow) {
        assertThat(transitionedTo(flow))
                .isInstanceOf(InitialRequestPayloadContinuation.class);
    }

    private static ByteBuf header(String value) {
        return Unpooled.copiedBuffer(
                value,
                StandardCharsets.US_ASCII);
    }
}
