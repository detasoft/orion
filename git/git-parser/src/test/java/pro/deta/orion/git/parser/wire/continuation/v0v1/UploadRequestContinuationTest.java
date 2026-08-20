package pro.deta.orion.git.parser.wire.continuation.v0v1;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.GitNativeClientOutput;
import pro.deta.orion.git.parser.wire.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.wire.advertisement.GitAdvertisedRef;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.git.parser.wire.capability.GitCapability;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestService;
import pro.deta.orion.git.parser.wire.continuation.exchange.LegacyUploadRequest;
import pro.deta.orion.git.parser.wire.error.GitGeneralException;
import pro.deta.orion.git.parser.wire.error.GitWireError;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.EMPTY_LEGACY_UPLOAD_CAPABILITY;
import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.EMPTY_LEGACY_UPLOAD_PACKET;
import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.INVALID_LEGACY_UPLOAD_OBJECT_ID;
import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.LATE_LEGACY_UPLOAD_CAPABILITIES;
import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.MISSING_LEGACY_UPLOAD_WANT;
import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.UNSUPPORTED_LEGACY_UPLOAD_COMMAND;
import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.UNSUPPORTED_LEGACY_UPLOAD_CONTROL;

class UploadRequestContinuationTest {
    private static final String FIRST_ID = "1".repeat(40);
    private static final String SECOND_ID = "a".repeat(40);

    @Test
    void parsesWantCapabilitiesAndFlushOneByteAtATime() {
        ByteBuf input = request(
                "want " + FIRST_ID
                        + " thin-pack side-band-64k thin-pack\n");
        Driver driver = new Driver();
        ContinuationFlow<ByteBuf> flow = null;
        try {
            while (input.isReadable()) {
                ByteBuf fragment = input.readRetainedSlice(1);
                try {
                    flow = driver.drive(fragment);
                } finally {
                    fragment.release();
                }
            }
        } finally {
            input.release();
        }

        LegacyUploadRequest request = completedRequest(flow);
        assertThat(request.initialRequest()).isSameAs(initialRequest());
        assertThat(request.wants())
                .containsExactly(GitObjectId.of(FIRST_ID));
        assertThat(request.capabilities())
                .containsExactly("thin-pack", "side-band-64k");
        assertThat(request.serverAdvertisement())
                .isSameAs(serverAdvertisement());
        assertThat(request.wants()).isUnmodifiable();
        assertThat(request.capabilities()).isUnmodifiable();
    }

    @Test
    void parsesMultipleWantsAndLeavesBytesAfterFlushUnread() {
        ByteBuf input = Unpooled.buffer();
        writeData(input, "want " + FIRST_ID + " thin-pack\n");
        writeData(input, "want " + SECOND_ID + "\n");
        writeData(input, "want " + FIRST_ID + "\n");
        writeFlush(input);
        input.writeByte('x');

        ContinuationFlow<ByteBuf> flow;
        try {
            flow = new Driver().drive(input);
            assertThat(input.toString(StandardCharsets.US_ASCII))
                    .isEqualTo("x");
        } finally {
            input.release();
        }

        assertThat(completedRequest(flow).wants())
                .containsExactly(
                        GitObjectId.of(FIRST_ID),
                        GitObjectId.of(SECOND_ID));
    }

    @Test
    void rejectsFlushBeforeWant() {
        assertError(
                process(control("0000")),
                MISSING_LEGACY_UPLOAD_WANT);
    }

    @Test
    void rejectsMalformedObjectId() {
        assertError(
                process(data("want not-an-object-id\n")),
                INVALID_LEGACY_UPLOAD_OBJECT_ID);
    }

    @Test
    void rejectsUnsupportedCommand() {
        assertError(
                process(data("deepen 1\n")),
                UNSUPPORTED_LEGACY_UPLOAD_COMMAND);
    }

    @Test
    void rejectsCapabilitiesOnLaterWant() {
        ByteBuf input = Unpooled.buffer();
        writeData(input, "want " + FIRST_ID + " thin-pack\n");
        writeData(input, "want " + SECOND_ID + " ofs-delta\n");

        ContinuationFlow<ByteBuf> flow;
        try {
            flow = new Driver().drive(input);
        } finally {
            input.release();
        }

        assertError(flow, LATE_LEGACY_UPLOAD_CAPABILITIES);
    }

    @Test
    void rejectsEmptyDataPacket() {
        assertError(
                process(control("0004")),
                EMPTY_LEGACY_UPLOAD_PACKET);
    }

    @Test
    void rejectsDelimiterPacket() {
        assertError(
                process(control("0001")),
                UNSUPPORTED_LEGACY_UPLOAD_CONTROL);
    }

    @Test
    void rejectsEmptyFirstCapability() {
        assertError(
                process(data("want " + FIRST_ID + " \n")),
                EMPTY_LEGACY_UPLOAD_CAPABILITY);
    }

    private static InitialRequestData initialRequest() {
        return InitialRequestHolder.VALUE;
    }

    private static GitV1Advertisement serverAdvertisement() {
        return AdvertisementHolder.VALUE;
    }

    private static LegacyUploadRequest completedRequest(
            ContinuationFlow<ByteBuf> flow) {
        assertThat(flow)
                .isInstanceOf(ContinuationFlow.Transition.class);
        Continuation<?> next =
                ((ContinuationFlow.Transition<?>) flow).next();
        assertThat(next)
                .isInstanceOf(UploadNegotiationContinuation.class);
        return ((UploadNegotiationContinuation) next).request();
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
                                    .hasMessageContaining(kind.name())
                                    .hasMessageContaining(
                                            kind.getMessage());
                        });
    }

    private static ContinuationFlow<ByteBuf> process(
            ByteBuf input) {
        try {
            return new Driver().drive(input);
        } finally {
            input.release();
        }
    }

    private static ByteBuf request(String firstWant) {
        ByteBuf input = Unpooled.buffer();
        writeData(input, firstWant);
        writeFlush(input);
        return input;
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

    private static final class InitialRequestHolder {
        private static final InitialRequestData VALUE =
                new InitialRequestData(
                        InitialRequestService.UPLOAD_PACK,
                        "/demo.git",
                        "localhost",
                        Map.of());
    }

    private static final class AdvertisementHolder {
        private static final GitV1Advertisement VALUE =
                new GitV1Advertisement(
                        List.of(
                                GitCapability.THIN_PACK,
                                GitCapability.OFS_DELTA),
                        List.of(GitAdvertisedRef.direct(
                                FIRST_ID,
                                "refs/heads/main")));
    }

    private static final class Driver {
        private Continuation<ByteBuf> current =
                new UploadRequestContinuation(
                        defaultContext(),
                        initialRequest(),
                        serverAdvertisement());

        private ContinuationFlow<ByteBuf> drive(ByteBuf input) {
            while (true) {
                ContinuationFlow<ByteBuf> flow =
                        current.process(input);
                if (flow instanceof
                        ContinuationFlow.Transition<ByteBuf> transition) {
                    current = transition.next();
                    if (current instanceof
                            UploadNegotiationContinuation
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

    private static GitMinimalWireMachine.Context defaultContext() {
        ByteBuf outbound = UnpooledByteBufAllocator.DEFAULT.buffer(
                GitNativeClientOutput.BUFFER_CAPACITY,
                GitNativeClientOutput.BUFFER_CAPACITY);
        return GitMinimalWireMachine.testContext(
                UnpooledByteBufAllocator.DEFAULT,
                new GitNativeClientOutput(outbound),
                new InMemoryNativeGitRepositoryProvider(),
                GitNativeRepositoryAccessHook.ALLOW_ALL);
    }
}
