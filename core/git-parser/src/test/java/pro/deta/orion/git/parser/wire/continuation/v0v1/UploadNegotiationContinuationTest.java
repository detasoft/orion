package pro.deta.orion.git.parser.wire.continuation.v0v1;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.upload.NativeFetchRequest;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.GitNativeClientOutput;
import pro.deta.orion.git.parser.wire.advertisement.GitAdvertisedRef;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.git.parser.wire.capability.GitCapability;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestService;
import pro.deta.orion.git.parser.wire.continuation.exchange.LegacyUploadNegotiation;
import pro.deta.orion.git.parser.wire.continuation.exchange.LegacyUploadRequest;
import pro.deta.orion.git.parser.wire.error.GitGeneralException;
import pro.deta.orion.git.parser.wire.error.GitWireError;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.EMPTY_LEGACY_UPLOAD_NEGOTIATION_PACKET;
import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.INVALID_LEGACY_UPLOAD_HAVE_OBJECT_ID;
import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.UNSUPPORTED_LEGACY_UPLOAD_NEGOTIATION_COMMAND;
import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.UNSUPPORTED_LEGACY_UPLOAD_NEGOTIATION_CONTROL;

class UploadNegotiationContinuationTest {
    private static final String FIRST_ID = "1".repeat(40);
    private static final String SECOND_ID = "a".repeat(40);

    @Test
    void parsesHaveAndDoneAndLeavesTrailingBytesUnread() {
        ByteBuf input = Unpooled.buffer();
        writeData(input, "have " + FIRST_ID + "\n");
        writeData(input, "done\n");
        input.writeByte('x');
        Driver driver = new Driver(context());

        ContinuationFlow<ByteBuf> flow;
        try {
            flow = driver.drive(input);
            assertThat(input.toString(StandardCharsets.US_ASCII))
                    .isEqualTo("x");
        } finally {
            input.release();
        }

        completedResponse(flow);
    }

    @Test
    void parsesFragmentedHavesAcrossFlushRounds() {
        ByteBuf outbound = fixedOutput();
        GitNativeClientOutput clientOutput =
                new GitNativeClientOutput(outbound);
        Driver driver = new Driver(context(clientOutput));
        ByteBuf input = Unpooled.buffer();
        writeData(input, "have " + FIRST_ID + "\n");
        writeData(input, "have " + SECOND_ID + "\n");
        writeFlush(input);
        writeData(input, "have " + FIRST_ID + "\n");
        writeData(input, "done");

        ContinuationFlow<ByteBuf> flow;
        try {
            flow = driver.driveOneByteAtATime(input);
        } finally {
            input.release();
        }

        try {
            assertThat(outbound.toString(StandardCharsets.US_ASCII))
                    .isEqualTo("0008NAK\n");
            completedResponse(flow);
        } finally {
            outbound.release();
        }
    }

    @Test
    void yieldsWhenNakOutputNeedsStreaming() {
        ByteBuf outbound = fixedOutput();
        outbound.writerIndex(outbound.capacity());
        List<ByteBuf> sent = new ArrayList<>();
        GitNativeClientOutput clientOutput = new GitNativeClientOutput(
                outbound,
                sent::add);
        UploadNegotiationContinuation negotiation =
                new UploadNegotiationContinuation(
                        context(clientOutput),
                        request());
        UploadNegotiationResponseContinuation response =
                new UploadNegotiationResponseContinuation(
                        context(clientOutput),
                        negotiation);
        ByteBuf input = Unpooled.buffer();

        ContinuationFlow<ByteBuf> flow;
        try {
            flow = response.process(input);
        } finally {
            input.release();
        }

        try {
            ContinuationFlow.TransitionAndYield<ByteBuf> yielded =
                    (ContinuationFlow.TransitionAndYield<ByteBuf>) flow;
            yielded.task().run();
            assertThat(yielded.next().process(Unpooled.EMPTY_BUFFER))
                    .isEqualTo(
                            ContinuationFlow.transition(negotiation));
            assertThat(sent).hasSize(2);
            assertThat(sent.getLast()
                    .toString(StandardCharsets.US_ASCII))
                    .isEqualTo("0008NAK\n");
        } finally {
            for (ByteBuf submitted : sent) {
                submitted.release();
            }
            outbound.release();
        }
    }

    @Test
    void completesWithErrorWhenNakOutputRejectsOperation() {
        ByteBuf outbound = fixedOutput();
        outbound.writerIndex(outbound.capacity());
        GitNativeClientOutput clientOutput = new GitNativeClientOutput(
                outbound,
                ByteBuf::release);
        GitNativeClientOutput.SendResult active =
                clientOutput.sendNak();
        assertThat(active)
                .isInstanceOf(
                        GitNativeClientOutput.SendResult.Streaming.class);
        UploadNegotiationContinuation negotiation =
                new UploadNegotiationContinuation(
                        context(clientOutput),
                        request());
        UploadNegotiationResponseContinuation response =
                new UploadNegotiationResponseContinuation(
                        context(clientOutput),
                        negotiation);
        ByteBuf input = Unpooled.buffer();

        ContinuationFlow<ByteBuf> flow;
        try {
            flow = response.process(input);
        } finally {
            input.release();
            outbound.release();
        }

        assertThat(flow)
                .isInstanceOf(ContinuationFlow.Transition.class);
        assertThat(((ContinuationFlow.Transition<?>) flow).next())
                .isInstanceOfSatisfying(
                        Continuation.CompletedError.class,
                        error -> assertThat(error.message())
                                .contains("already in progress"));
    }

    @Test
    void rejectsMalformedHaveObjectId() {
        assertError(
                process(data("have not-an-object-id\n")),
                INVALID_LEGACY_UPLOAD_HAVE_OBJECT_ID);
    }

    @Test
    void rejectsUnsupportedCommand() {
        assertError(
                process(data("want " + FIRST_ID + "\n")),
                UNSUPPORTED_LEGACY_UPLOAD_NEGOTIATION_COMMAND);
    }

    @Test
    void rejectsEmptyDataPacket() {
        assertError(
                process(control("0004")),
                EMPTY_LEGACY_UPLOAD_NEGOTIATION_PACKET);
    }

    @Test
    void rejectsDelimiterAndResponseEnd() {
        assertError(
                process(control("0001")),
                UNSUPPORTED_LEGACY_UPLOAD_NEGOTIATION_CONTROL);
        assertError(
                process(control("0002")),
                UNSUPPORTED_LEGACY_UPLOAD_NEGOTIATION_CONTROL);
    }

    @Test
    void negotiatesFetchCapabilitiesByClientAndServerNames() {
        List<CapabilityCase> cases = List.of(
                new CapabilityCase(Set.of(), List.of(), false, false),
                new CapabilityCase(
                        Set.of("thin-pack", "ofs-delta"),
                        List.of(
                                GitCapability.THIN_PACK,
                                GitCapability.OFS_DELTA),
                        true,
                        true),
                new CapabilityCase(
                        Set.of("thin-pack", "ofs-delta"),
                        List.of(),
                        false,
                        false),
                new CapabilityCase(
                        Set.of(),
                        List.of(
                                GitCapability.THIN_PACK,
                                GitCapability.OFS_DELTA),
                        false,
                        false),
                new CapabilityCase(
                        Set.of("thin-pack"),
                        List.of(
                                GitCapability.THIN_PACK,
                                GitCapability.OFS_DELTA),
                        true,
                        false),
                new CapabilityCase(
                        Set.of("ofs-delta"),
                        List.of(
                                GitCapability.THIN_PACK,
                                GitCapability.OFS_DELTA),
                        false,
                        true),
                new CapabilityCase(
                        Set.of("thin-pack", "ofs-delta"),
                        List.of(GitCapability.THIN_PACK),
                        true,
                        false),
                new CapabilityCase(
                        Set.of("thin-pack", "ofs-delta"),
                        List.of(GitCapability.OFS_DELTA),
                        false,
                        true));

        for (CapabilityCase capabilityCase : cases) {
            LegacyUploadRequest request = request(
                    capabilityCase.requested(),
                    capabilityCase.advertised());
            LegacyUploadNegotiation negotiation =
                    new LegacyUploadNegotiation(
                            request,
                            Set.of(GitObjectId.of(SECOND_ID)));

            NativeFetchRequest negotiatedRequest =
                    negotiation.nativeFetchRequest();
            assertThat(negotiatedRequest.wants())
                    .containsExactly(GitObjectId.of(FIRST_ID));
            assertThat(negotiatedRequest.haves())
                    .containsExactly(GitObjectId.of(SECOND_ID));
            assertThat(negotiatedRequest.done()).isTrue();
            assertThat(negotiatedRequest.thinPack())
                    .isEqualTo(capabilityCase.thinPack());
            assertThat(negotiatedRequest.ofsDelta())
                    .isEqualTo(capabilityCase.ofsDelta());
        }
    }

    private static ContinuationFlow<ByteBuf> process(ByteBuf input) {
        try {
            return new Driver(context()).drive(input);
        } finally {
            input.release();
        }
    }

    private static UploadResponseContinuation completedResponse(
            ContinuationFlow<ByteBuf> flow) {
        assertThat(flow)
                .isInstanceOf(ContinuationFlow.Transition.class);
        Continuation<?> next =
                ((ContinuationFlow.Transition<?>) flow).next();
        assertThat(next)
                .isInstanceOf(UploadResponseContinuation.class);
        return (UploadResponseContinuation) next;
    }

    private static void assertError(
            ContinuationFlow<ByteBuf> flow,
            GitWireError.Kind kind) {
        assertThat(flow)
                .isInstanceOf(ContinuationFlow.Transition.class);
        Continuation<?> next =
                ((ContinuationFlow.Transition<?>) flow).next();
        assertThat(next)
                .isInstanceOfSatisfying(
                        Continuation.CompletedError.class,
                        error -> {
                            assertThat(error.message())
                                    .isEqualTo(kind.getMessage());
                            assertThat(error.throwable())
                                    .isInstanceOf(GitGeneralException.class)
                                    .hasMessageContaining(kind.name());
                        });
    }

    private static GitMinimalWireMachine.Context context() {
        return context(new GitNativeClientOutput(fixedOutput()));
    }

    private static GitMinimalWireMachine.Context context(
            GitNativeClientOutput output) {
        return GitMinimalWireMachine.testContext(
                UnpooledByteBufAllocator.DEFAULT,
                output);
    }

    private static ByteBuf fixedOutput() {
        return Unpooled.buffer(
                GitNativeClientOutput.BUFFER_CAPACITY,
                GitNativeClientOutput.BUFFER_CAPACITY);
    }

    private static LegacyUploadRequest request() {
        return RequestHolder.VALUE;
    }

    private static LegacyUploadRequest request(
            Set<String> capabilities,
            List<GitCapability> serverCapabilities) {
        return new LegacyUploadRequest(
                RequestHolder.INITIAL_REQUEST,
                Set.of(GitObjectId.of(FIRST_ID)),
                capabilities,
                new GitV1Advertisement(
                        serverCapabilities,
                        List.of(GitAdvertisedRef.direct(
                                FIRST_ID,
                                "refs/heads/main"))));
    }

    private static ByteBuf data(String payload) {
        ByteBuf input = Unpooled.buffer();
        writeData(input, payload);
        return input;
    }

    private static ByteBuf control(String value) {
        return Unpooled.copiedBuffer(
                value,
                StandardCharsets.US_ASCII);
    }

    private static void writeData(ByteBuf output, String value) {
        byte[] payload = value.getBytes(StandardCharsets.US_ASCII);
        output.writeCharSequence(
                "%04x".formatted(payload.length + 4),
                StandardCharsets.US_ASCII);
        output.writeBytes(payload);
    }

    private static void writeFlush(ByteBuf output) {
        output.writeCharSequence("0000", StandardCharsets.US_ASCII);
    }

    private static final class RequestHolder {
        private static final InitialRequestData INITIAL_REQUEST =
                new InitialRequestData(
                        InitialRequestService.UPLOAD_PACK,
                        "/demo.git",
                        "localhost",
                        Map.of());
        private static final LegacyUploadRequest VALUE =
                request(
                        Set.of("thin-pack", "ofs-delta"),
                        List.of(
                                GitCapability.THIN_PACK,
                                GitCapability.OFS_DELTA));
    }

    private record CapabilityCase(
            Set<String> requested,
            List<GitCapability> advertised,
            boolean thinPack,
            boolean ofsDelta) {
    }

    private static final class Driver {
        private Continuation<ByteBuf> current;

        private Driver(GitMinimalWireMachine.Context context) {
            current = new UploadNegotiationContinuation(
                    context,
                    request());
        }

        private ContinuationFlow<ByteBuf> driveOneByteAtATime(
                ByteBuf input) {
            ContinuationFlow<ByteBuf> flow = null;
            while (input.isReadable()) {
                ByteBuf fragment = input.readRetainedSlice(1);
                try {
                    flow = drive(fragment);
                } finally {
                    fragment.release();
                }
            }
            return flow;
        }

        private ContinuationFlow<ByteBuf> drive(ByteBuf input) {
            while (true) {
                ContinuationFlow<ByteBuf> flow =
                        current.process(input);
                if (flow instanceof
                        ContinuationFlow.Transition<ByteBuf> transition) {
                    current = transition.next();
                    if (current instanceof UploadResponseContinuation
                            || current instanceof
                            Continuation.CompletedError<?>) {
                        return flow;
                    }
                    continue;
                }
                if (flow instanceof ContinuationFlow.Continue<ByteBuf>) {
                    continue;
                }
                return flow;
            }
        }
    }
}
