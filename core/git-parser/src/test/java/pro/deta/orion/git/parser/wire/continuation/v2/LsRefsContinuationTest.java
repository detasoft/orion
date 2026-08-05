package pro.deta.orion.git.parser.wire.continuation.v2;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.continuation.ContinuationRuntime;
import pro.deta.orion.continuation.RuntimeFlow;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.GitNativeClientOutput;
import pro.deta.orion.git.parser.wire.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.wire.GitNativeRepositoryService;
import pro.deta.orion.git.parser.wire.GitWireConfiguration;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestService;
import pro.deta.orion.git.parser.wire.error.GitGeneralException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.INVALID_PROTOCOL_V2_REQUEST;

class LsRefsContinuationTest {
    private static final String NULL_ID = "0".repeat(40);

    @Test
    void payloadContinuationDoesNotRetainRawByteBuf() {
        assertThat(Arrays.stream(
                LsRefsArgumentPayloadContinuation.class
                        .getDeclaredFields()))
                .noneMatch(field -> ByteBuf.class
                        .isAssignableFrom(field.getType()));
    }

    @Test
    void parsesOneByteFragmentsAndWritesRequestedRefsExactly() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                repository(provider);
        GitObjectId main = repository.writeObject(
                ObjectType.COMMIT,
                "main".getBytes(StandardCharsets.US_ASCII));
        GitObjectId target = repository.writeObject(
                ObjectType.COMMIT,
                "target".getBytes(StandardCharsets.US_ASCII));
        GitObjectId tag = repository.writeObject(
                ObjectType.TAG,
                tagData(target.value()));
        repository.updateRef(
                "refs/heads/main",
                NULL_ID,
                main.value());
        repository.updateRef(
                "refs/tags/v1",
                NULL_ID,
                tag.value());
        ByteBuf outbound = outputBuffer();
        Driver driver = new Driver(
                context(provider, outbound),
                initialRequest());
        ByteBuf input = request(
                "peel\n",
                "symrefs\n",
                "unborn\n",
                "ref-prefix HEAD\n",
                "ref-prefix refs/\n",
                "ref-prefix refs/heads/\n");
        try {
            driveOneByteAtATime(driver, input);

            assertThat(driver.current)
                    .isInstanceOf(UploadCommandContinuation.class);
            assertThat(outbound.toString(StandardCharsets.US_ASCII))
                    .isEqualTo(
                            packet(
                                    main.value()
                                            + " HEAD symref-target:refs/heads/main\n")
                                    + packet(
                                            main.value()
                                                    + " refs/heads/main\n")
                                    + packet(
                                            tag.value()
                                                    + " refs/tags/v1 peeled:"
                                                    + target.value()
                                                    + "\n")
                                    + "0000");
        } finally {
            outbound.release();
        }
    }

    @Test
    void writesUnbornHeadAndEmptyResponseExactly() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();

        ByteBuf unbornOutput = outputBuffer();
        try {
            Driver unborn = new Driver(
                    context(provider, unbornOutput),
                    initialRequest());
            drive(unborn, request(
                    "symrefs\n",
                    "unborn\n",
                    "ref-prefix HEAD\n"));

            assertThat(unborn.current)
                    .isInstanceOf(UploadCommandContinuation.class);
            assertThat(unbornOutput.toString(StandardCharsets.US_ASCII))
                    .isEqualTo(
                            packet(
                                    "unborn HEAD symref-target:refs/heads/main\n")
                                    + "0000");
        } finally {
            unbornOutput.release();
        }

        ByteBuf emptyOutput = outputBuffer();
        try {
            Driver empty = new Driver(
                    context(provider, emptyOutput),
                    initialRequest());
            drive(empty, request(
                    "server-option ignored value\n",
                    "ref-prefix refs/missing/\n"));

            assertThat(empty.current)
                    .isInstanceOf(UploadCommandContinuation.class);
            assertThat(emptyOutput.toString(StandardCharsets.US_ASCII))
                    .isEqualTo("0000");
        } finally {
            emptyOutput.release();
        }
    }

    @Test
    void rejectsMalformedArgumentsAndUnsupportedControls() {
        assertInvalid(request("peel extra\n"));
        assertInvalid(request("ref-prefix\n"));
        assertInvalid(request("ref-prefix \n"));
        assertInvalid(request("\n"));
        assertInvalid(rawRequest(new byte[] {'x', (byte) 0x80, '\n'}));
        assertInvalid(control("0004"));
        assertInvalid(control("0001"));
        assertInvalid(control("0002"));
    }

    @Test
    void acceptsJGitStyleArgumentsWithoutTrailingLineFeed() {
        ByteBuf outbound = outputBuffer();
        Driver driver = new Driver(
                context(
                        new InMemoryNativeGitRepositoryProvider(),
                        outbound),
                initialRequest());
        ByteBuf input = rawRequest(
                "peel",
                "symrefs",
                "ref-prefix refs/missing/");
        try {
            driver.drive(input);

            assertThat(driver.current)
                    .isInstanceOf(UploadCommandContinuation.class);
            assertThat(outbound.toString(StandardCharsets.US_ASCII))
                    .isEqualTo("0000");
        } finally {
            input.release();
            outbound.release();
        }
    }

    @Test
    void rejectsUnbornWhenFeatureIsDisabled() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        ByteBuf outbound = outputBuffer();
        Driver driver = new Driver(
                context(
                        provider,
                        new GitNativeClientOutput(outbound),
                        new GitWireConfiguration.ProtocolV2(
                                true, false, true, false)),
                initialRequest());
        ByteBuf input = request(
                "unborn\n");
        try {
            driver.drive(input);

            assertInvalid(driver.current);
        } finally {
            input.release();
            outbound.release();
        }
    }

    @Test
    void boundsRetainedRefPrefixCount() {
        ByteBuf accepted = Unpooled.buffer();
        for (int index = 0;
                index < LsRefsContinuation.MAX_REF_PREFIX_COUNT;
                index++) {
            writeData(accepted, "ref-prefix refs/missing/\n");
        }
        accepted.writeCharSequence("0000", StandardCharsets.US_ASCII);

        ByteBuf acceptedOutput = outputBuffer();
        Driver acceptedDriver = new Driver(
                context(
                        new InMemoryNativeGitRepositoryProvider(),
                        acceptedOutput),
                initialRequest());
        try {
            drive(acceptedDriver, accepted);
            assertThat(acceptedDriver.current)
                    .isInstanceOf(UploadCommandContinuation.class);
            assertThat(acceptedOutput.toString(
                    StandardCharsets.US_ASCII))
                    .isEqualTo("0000");
        } finally {
            acceptedOutput.release();
        }

        ByteBuf rejected = Unpooled.buffer();
        for (int index = 0;
                index <= LsRefsContinuation.MAX_REF_PREFIX_COUNT;
                index++) {
            writeData(rejected, "ref-prefix refs/missing/\n");
        }
        rejected.writeCharSequence("0000", StandardCharsets.US_ASCII);
        assertInvalid(rejected);
    }

    @Test
    void boundsRetainedCumulativeRefPrefixCharacters() {
        int firstLength = 65_504;
        int secondLength =
                LsRefsContinuation.MAX_REF_PREFIX_CHARS - firstLength;
        ByteBuf accepted = request(
                "ref-prefix " + "a".repeat(firstLength) + "\n",
                "ref-prefix " + "b".repeat(secondLength) + "\n");

        ByteBuf acceptedOutput = outputBuffer();
        Driver acceptedDriver = new Driver(
                context(
                        new InMemoryNativeGitRepositoryProvider(),
                        acceptedOutput),
                initialRequest());
        try {
            drive(acceptedDriver, accepted);
            assertThat(acceptedDriver.current)
                    .isInstanceOf(UploadCommandContinuation.class);
        } finally {
            acceptedOutput.release();
        }

        assertInvalid(request(
                "ref-prefix " + "a".repeat(firstLength) + "\n",
                "ref-prefix " + "b".repeat(secondLength) + "\n",
                "ref-prefix x\n"));
    }

    @Test
    void completedResponseDoesNotConsumeFollowingCommandBytes() {
        ByteBuf outbound = outputBuffer();
        ByteBuf input = request("ref-prefix refs/missing/\n");
        input.writeCharSequence("0001", StandardCharsets.US_ASCII);
        Driver driver = new Driver(
                context(
                        new InMemoryNativeGitRepositoryProvider(),
                        outbound),
                initialRequest());
        try {
            driver.drive(input);

            assertThat(driver.current)
                    .isInstanceOf(UploadCommandContinuation.class);
            assertThat(input.toString(StandardCharsets.US_ASCII))
                    .isEqualTo("0001");
        } finally {
            input.release();
            outbound.release();
        }
    }

    @Test
    void yieldsWhenOutputBufferIsInitiallyFull() {
        ByteBuf outbound = outputBuffer();
        outbound.writerIndex(outbound.capacity());
        GitNativeClientOutput output = new GitNativeClientOutput(
                outbound,
                ByteBuf::release);
        Driver driver = new Driver(
                context(
                        new InMemoryNativeGitRepositoryProvider(),
                        output),
                initialRequest());
        ByteBuf input = request("ref-prefix refs/missing/\n");
        try {
            driver.drive(input);

            assertThat(driver.lastFlow)
                    .isInstanceOfSatisfying(
                            ContinuationFlow.TransitionAndYield.class,
                            yielded -> {
                                yielded.task().run();
                                assertThat(yielded.next().process(input))
                                        .isInstanceOfSatisfying(
                                                ContinuationFlow.Transition.class,
                                                resumed -> assertThat(
                                                        resumed.next())
                                                        .isInstanceOf(
                                                                UploadCommandContinuation.class));
                            });
        } finally {
            input.release();
            outbound.release();
        }
    }

    @Test
    void runtimeFreezesFollowingInputAcrossStreamingYield() {
        ByteBuf outbound = outputBuffer();
        outbound.writerIndex(outbound.capacity());
        List<ByteBuf> submitted = new ArrayList<>();
        GitNativeClientOutput output = new GitNativeClientOutput(
                outbound,
                submitted::add);
        ContinuationRuntime<ByteBuf> runtime =
                new ContinuationRuntime<>(
                        new LsRefsContinuation(
                                context(
                                        new InMemoryNativeGitRepositoryProvider(),
                                        output),
                                initialRequest()));
        ByteBuf input = request("ref-prefix refs/missing/\n");
        input.writeCharSequence("0001", StandardCharsets.US_ASCII);
        try {
            RuntimeFlow flow = runtime.accept(input);

            assertThat(flow)
                    .isInstanceOf(ContinuationFlow.Yield.class);
            assertThat(runtime.isYielding()).isTrue();
            assertThat(input.toString(StandardCharsets.US_ASCII))
                    .isEqualTo("0001");

            ((ContinuationFlow.Yield<?>) flow).task().run();
            assertThat(submitted.getLast().toString(
                    StandardCharsets.US_ASCII))
                    .isEqualTo("0000");

            assertThat(runtime.resumeTask())
                    .isInstanceOf(RuntimeFlow.Terminal.class);
            assertThat(runtime.isYielding()).isFalse();
            assertThat(runtime.terminal()).isTrue();
            assertThat(input.isReadable()).isFalse();
        } finally {
            for (ByteBuf buffer : submitted) {
                buffer.release();
            }
            input.release();
            outbound.release();
        }
    }

    @Test
    void runtimeCompletesWithErrorWhenStreamingDeliveryFails() {
        ByteBuf outbound = outputBuffer();
        outbound.writerIndex(outbound.capacity());
        IllegalStateException failure =
                new IllegalStateException("delivery failed");
        GitNativeClientOutput output = new GitNativeClientOutput(
                outbound,
                ignored -> {
                    throw failure;
                });
        InspectableRuntime runtime = new InspectableRuntime(
                new LsRefsContinuation(
                        context(
                                new InMemoryNativeGitRepositoryProvider(),
                                output),
                        initialRequest()));
        ByteBuf input = request("ref-prefix refs/missing/\n");
        try {
            RuntimeFlow flow = runtime.accept(input);

            assertThat(flow)
                    .isInstanceOf(ContinuationFlow.Yield.class);
            assertThatCode(
                    ((ContinuationFlow.Yield<?>) flow).task()::run)
                    .doesNotThrowAnyException();

            assertThat(runtime.resumeTask())
                    .isInstanceOf(RuntimeFlow.Terminal.class);
            assertThat(runtime.currentContinuation())
                    .isInstanceOfSatisfying(
                            Continuation.CompletedError.class,
                            error -> {
                                assertThat(error.message()).isEqualTo(
                                        "Failed to deliver"
                                                + " serialized client output");
                                assertThat(error.throwable())
                                        .isSameAs(failure);
                            });
        } finally {
            input.release();
            outbound.release();
        }
    }

    @Test
    void propagatesExpectedOutputFailure() {
        ByteBuf outbound = outputBuffer();
        outbound.writerIndex(outbound.capacity());
        GitNativeClientOutput output = new GitNativeClientOutput(
                outbound,
                ByteBuf::release);
        assertThat(output.sendNak())
                .isInstanceOf(
                        GitNativeClientOutput.SendResult.Streaming.class);
        Driver driver = new Driver(
                context(
                        new InMemoryNativeGitRepositoryProvider(),
                        output),
                initialRequest());
        ByteBuf input = request("ref-prefix refs/missing/\n");
        try {
            driver.drive(input);

            assertThat(driver.current)
                    .isInstanceOfSatisfying(
                            Continuation.CompletedError.class,
                            error -> assertThat(error.message())
                                    .isEqualTo(
                                            "Client output operation is already in progress"));
        } finally {
            input.release();
            outbound.release();
        }
    }

    @Test
    void wrapsMissingRepositoryFailure() {
        ByteBuf outbound = outputBuffer();
        Driver driver = new Driver(
                context(
                        new InMemoryNativeGitRepositoryProvider(),
                        outbound),
                initialRequest("/"));
        ByteBuf input = request("ref-prefix refs/\n");
        try {
            driver.drive(input);

            assertThat(driver.current)
                    .isInstanceOfSatisfying(
                            Continuation.CompletedError.class,
                            error -> {
                                assertThat(error.message())
                                        .isEqualTo(
                                                "Failed to serve protocol v2 ls-refs");
                                assertThat(error.throwable())
                                        .isInstanceOf(
                                                IllegalStateException.class)
                                        .hasMessageContaining(
                                                "Native repository does not exist: /");
                            });
        } finally {
            input.release();
            outbound.release();
        }
    }

    private static void assertInvalid(ByteBuf input) {
        ByteBuf outbound = outputBuffer();
        Driver driver = new Driver(
                context(
                        new InMemoryNativeGitRepositoryProvider(),
                        outbound),
                initialRequest());
        try {
            driver.drive(input);
            assertInvalid(driver.current);
        } finally {
            input.release();
            outbound.release();
        }
    }

    private static void assertInvalid(
            Continuation<ByteBuf> continuation) {
        assertThat(continuation)
                .isInstanceOfSatisfying(
                        Continuation.CompletedError.class,
                        error -> {
                            assertThat(error.message())
                                    .isEqualTo(
                                            INVALID_PROTOCOL_V2_REQUEST
                                                    .getMessage());
                            assertThat(error.throwable())
                                    .isInstanceOf(
                                            GitGeneralException.class)
                                    .hasMessageContaining(
                                            INVALID_PROTOCOL_V2_REQUEST
                                                    .name());
                        });
    }

    private static void driveOneByteAtATime(
            Driver driver,
            ByteBuf input) {
        try {
            while (input.isReadable()) {
                ByteBuf fragment = input.readRetainedSlice(1);
                try {
                    driver.drive(fragment);
                } finally {
                    fragment.release();
                }
            }
        } finally {
            input.release();
        }
    }

    private static void drive(Driver driver, ByteBuf input) {
        try {
            driver.drive(input);
        } finally {
            input.release();
        }
    }

    private static ByteBuf request(String... arguments) {
        ByteBuf input = Unpooled.buffer();
        for (String argument : arguments) {
            writeData(input, argument);
        }
        input.writeCharSequence("0000", StandardCharsets.US_ASCII);
        return input;
    }

    private static ByteBuf rawRequest(byte[] payload) {
        ByteBuf input = Unpooled.buffer();
        input.writeCharSequence(
                "%04x".formatted(payload.length + 4),
                StandardCharsets.US_ASCII);
        input.writeBytes(payload);
        input.writeCharSequence("0000", StandardCharsets.US_ASCII);
        return input;
    }

    private static ByteBuf rawRequest(String... payloads) {
        ByteBuf input = Unpooled.buffer();
        for (String payload : payloads) {
            byte[] bytes = payload.getBytes(StandardCharsets.US_ASCII);
            input.writeCharSequence(
                    "%04x".formatted(bytes.length + 4),
                    StandardCharsets.US_ASCII);
            input.writeBytes(bytes);
        }
        input.writeCharSequence("0000", StandardCharsets.US_ASCII);
        return input;
    }

    private static ByteBuf control(String value) {
        return Unpooled.copiedBuffer(value, StandardCharsets.US_ASCII);
    }

    private static void writeData(ByteBuf output, String value) {
        byte[] payload = value.getBytes(StandardCharsets.US_ASCII);
        output.writeCharSequence(
                "%04x".formatted(payload.length + 4),
                StandardCharsets.US_ASCII);
        output.writeBytes(payload);
    }

    private static String packet(String payload) {
        return "%04x%s".formatted(payload.length() + 4, payload);
    }

    private static byte[] tagData(String targetId) {
        return ("object " + targetId + "\n"
                + "type commit\n"
                + "tag v1\n\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static NativeGitRepository repository(
            InMemoryNativeGitRepositoryProvider provider) {
        return provider.exists("/demo.git")
                ? provider.find("/demo.git").valueOrFailure("repository")
                : provider.create("/demo.git").valueOrFailure("repository");
    }

    private static GitMinimalWireMachine.Context context(
            InMemoryNativeGitRepositoryProvider provider,
            ByteBuf outbound) {
        return context(
                provider,
                new GitNativeClientOutput(outbound));
    }

    private static GitMinimalWireMachine.Context context(
            InMemoryNativeGitRepositoryProvider provider,
            GitNativeClientOutput output) {
        repository(provider);
        return GitMinimalWireMachine.testContext(
                UnpooledByteBufAllocator.DEFAULT,
                output,
                new GitNativeRepositoryService(
                        provider,
                        GitNativeRepositoryAccessHook.ALLOW_ALL));
    }

    private static GitMinimalWireMachine.Context context(
            InMemoryNativeGitRepositoryProvider provider,
            GitNativeClientOutput output,
            GitWireConfiguration.ProtocolV2 protocolV2) {
        repository(provider);
        GitWireConfiguration supported =
                GitWireConfiguration.allSupported();
        return GitMinimalWireMachine.testContext(
                UnpooledByteBufAllocator.DEFAULT,
                output,
                provider,
                GitNativeRepositoryAccessHook.ALLOW_ALL,
                new GitWireConfiguration(
                        supported.uploadPack(),
                        supported.receivePack(),
                        protocolV2));
    }

    private static InitialRequestData initialRequest() {
        return initialRequest("/demo.git");
    }

    private static InitialRequestData initialRequest(String path) {
        return new InitialRequestData(
                InitialRequestService.UPLOAD_PACK,
                path,
                "localhost",
                Map.of());
    }

    private static ByteBuf outputBuffer() {
        return Unpooled.buffer(
                GitNativeClientOutput.BUFFER_CAPACITY,
                GitNativeClientOutput.BUFFER_CAPACITY);
    }

    private static final class Driver {
        private Continuation<ByteBuf> current;
        private ContinuationFlow<ByteBuf> lastFlow;

        private Driver(
                GitMinimalWireMachine.Context context,
                InitialRequestData data) {
            current = new LsRefsContinuation(context, data);
        }

        private void drive(ByteBuf input) {
            while (true) {
                lastFlow = current.process(input);
                if (lastFlow instanceof
                        ContinuationFlow.Transition<ByteBuf> transition) {
                    current = transition.next();
                    if (current instanceof UploadCommandContinuation
                            || current instanceof
                            Continuation.CompletedError<?>) {
                        return;
                    }
                    continue;
                }
                if (lastFlow instanceof
                        ContinuationFlow.TransitionAndYield<ByteBuf> yielded) {
                    current = yielded.next();
                }
                return;
            }
        }
    }

    private static final class InspectableRuntime
            extends ContinuationRuntime<ByteBuf> {

        private InspectableRuntime(Continuation<ByteBuf> initial) {
            super(initial);
        }

        private Continuation<ByteBuf> currentContinuation() {
            return current();
        }
    }
}
