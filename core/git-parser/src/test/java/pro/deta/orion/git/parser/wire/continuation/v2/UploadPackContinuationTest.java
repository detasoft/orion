package pro.deta.orion.git.parser.wire.continuation.v2;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.GitNativeClientOutput;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestService;
import pro.deta.orion.git.parser.wire.error.GitGeneralException;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.INVALID_PROTOCOL_V2_REQUEST;

class UploadPackContinuationTest {

    @Test
    void payloadContinuationDoesNotAccumulateRawPayload() {
        assertThat(Arrays.stream(
                UploadCommandPayloadContinuation.class
                        .getDeclaredFields()))
                .noneMatch(field -> field.getType() == byte[].class
                        || ByteBuf.class.isAssignableFrom(field.getType()));
    }

    @Test
    void advertisesCapabilitiesBeforeParsingCommand() {
        ByteBuf outbound = outputBuffer();
        ByteBuf input = Unpooled.wrappedBuffer(new byte[] {1});
        try {
            UploadPackContinuation continuation =
                    new UploadPackContinuation(
                            context(new GitNativeClientOutput(outbound)),
                            initialRequest());

            ContinuationFlow<ByteBuf> flow =
                    continuation.process(input);

            assertThat(flow)
                    .isInstanceOfSatisfying(
                            ContinuationFlow.Transition.class,
                            transition -> assertThat(transition.next())
                                    .isInstanceOf(
                                            UploadCommandContinuation.class));
            assertThat(outbound.toString(StandardCharsets.US_ASCII))
                    .isEqualTo(
                            "000eversion 2\n"
                                    + "0013ls-refs=unborn\n"
                                    + "000afetch\n"
                                    + "0012server-option\n"
                                    + "0000");
            assertThat(input.readerIndex()).isZero();
        } finally {
            input.release();
            outbound.release();
        }
    }

    @Test
    void transitionsAndYieldsWhenAdvertisementMustBeStreamed() {
        ByteBuf outbound = outputBuffer();
        outbound.writerIndex(outbound.capacity());
        ByteBuf input = Unpooled.wrappedBuffer(new byte[] {1});
        GitNativeClientOutput output = new GitNativeClientOutput(
                outbound,
                ByteBuf::release);
        try {
            ContinuationFlow.TransitionAndYield<ByteBuf> flow =
                    (ContinuationFlow.TransitionAndYield<ByteBuf>)
                            new UploadPackContinuation(
                                    context(output),
                                    initialRequest())
                                    .process(input);

            flow.task().run();
            assertThat(flow.next().process(input))
                    .isInstanceOfSatisfying(
                            ContinuationFlow.Transition.class,
                            resumed -> assertThat(resumed.next())
                                    .isInstanceOf(
                                            UploadCommandContinuation.class));
            assertThat(input.readerIndex()).isZero();
        } finally {
            input.release();
            outbound.release();
        }
    }

    @Test
    void parsesFragmentedLsRefsRequest() {
        ByteBuf input = request(
                "command=ls-refs\n");

        Continuation<ByteBuf> completed = driveOneByteAtATime(input);

        assertThat(completed)
                .isInstanceOf(LsRefsContinuation.class);
    }

    @Test
    void dispatchesFetchWithoutConsumingItsArguments() {
        ByteBuf input = Unpooled.buffer();
        writeData(input, "command=fetch\n");
        writeDelimiter(input);
        writeData(input, "want " + "1".repeat(40) + "\n");
        int argumentsLength = input.readableBytes()
                - ("command=fetch\n".length() + 8);

        Driver driver = new Driver();
        driver.drive(input);

        assertThat(driver.current)
                .isInstanceOf(FetchContinuation.class);
        assertThat(input.readableBytes()).isEqualTo(argumentsLength);
        input.release();
    }

    @Test
    void rejectsUnknownCommand() {
        assertInvalid(data("command=unknown\n"));
    }

    @Test
    void rejectsArgumentsBeforeDelimiter() {
        ByteBuf input = Unpooled.buffer();
        writeData(input, "command=fetch\n");
        writeData(input, "want " + "1".repeat(40) + "\n");
        writeDelimiter(input);
        writeFlush(input);

        assertInvalid(input);
    }

    @Test
    void rejectsResponseEnd() {
        ByteBuf input = Unpooled.buffer();
        writeData(input, "command=ls-refs\n");
        input.writeCharSequence("0002", StandardCharsets.US_ASCII);

        assertInvalid(input);
    }

    private static void assertInvalid(ByteBuf input) {
        Continuation<ByteBuf> completed = drive(input);
        assertThat(completed)
                .isInstanceOfSatisfying(
                        Continuation.CompletedError.class,
                        error -> {
                            assertThat(error.message())
                                    .isEqualTo(
                                            INVALID_PROTOCOL_V2_REQUEST
                                                    .getMessage());
                            assertThat(error.throwable())
                                    .isInstanceOf(GitGeneralException.class)
                                    .hasMessageContaining(
                                            INVALID_PROTOCOL_V2_REQUEST.name());
                        });
    }

    private static Continuation<ByteBuf> driveOneByteAtATime(
            ByteBuf input) {
        Driver driver = new Driver();
        try {
            while (input.isReadable()) {
                ByteBuf fragment = input.readRetainedSlice(1);
                try {
                    driver.drive(fragment);
                } finally {
                    fragment.release();
                }
            }
            return driver.current;
        } finally {
            input.release();
        }
    }

    private static Continuation<ByteBuf> drive(ByteBuf input) {
        try {
            Driver driver = new Driver();
            driver.drive(input);
            return driver.current;
        } finally {
            input.release();
        }
    }

    private static ByteBuf request(String command) {
        ByteBuf input = Unpooled.buffer();
        writeData(input, command);
        writeDelimiter(input);
        return input;
    }

    private static ByteBuf data(String value) {
        ByteBuf input = Unpooled.buffer();
        writeData(input, value);
        return input;
    }

    private static void writeData(ByteBuf output, String value) {
        byte[] payload = value.getBytes(StandardCharsets.US_ASCII);
        output.writeCharSequence(
                "%04x".formatted(payload.length + 4),
                StandardCharsets.US_ASCII);
        output.writeBytes(payload);
    }

    private static void writeDelimiter(ByteBuf output) {
        output.writeCharSequence("0001", StandardCharsets.US_ASCII);
    }

    private static void writeFlush(ByteBuf output) {
        output.writeCharSequence("0000", StandardCharsets.US_ASCII);
    }

    private static InitialRequestData initialRequest() {
        return InitialRequestHolder.VALUE;
    }

    private static GitMinimalWireMachine.Context context(
            GitNativeClientOutput output) {
        return GitMinimalWireMachine.testContext(
                UnpooledByteBufAllocator.DEFAULT,
                output);
    }

    private static ByteBuf outputBuffer() {
        return Unpooled.buffer(
                GitNativeClientOutput.BUFFER_CAPACITY,
                GitNativeClientOutput.BUFFER_CAPACITY);
    }

    private static final class InitialRequestHolder {
        private static final InitialRequestData VALUE =
                new InitialRequestData(
                        InitialRequestService.UPLOAD_PACK,
                        "/demo.git",
                        "localhost",
                        Map.of());
    }

    private static final class Driver {
        private Continuation<ByteBuf> current =
                new UploadCommandContinuation(
                        GitMinimalWireMachine.testContext(
                                UnpooledByteBufAllocator.DEFAULT),
                        initialRequest());

        private void drive(ByteBuf input) {
            while (true) {
                ContinuationFlow<ByteBuf> flow =
                        current.process(input);
                if (flow instanceof
                        ContinuationFlow.Transition<ByteBuf> transition) {
                    current = transition.next();
                    if (current instanceof LsRefsContinuation
                            || current instanceof FetchContinuation
                            || current instanceof
                            Continuation.CompletedError<?>) {
                        return;
                    }
                    continue;
                }
                if (flow instanceof ContinuationFlow.Continue<ByteBuf>) {
                    continue;
                }
                return;
            }
        }
    }
}
