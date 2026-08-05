package pro.deta.orion.git.parser.wire.continuation.v2;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.nativestorage.upload.NativeFetchRequest;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.GitNativeClientOutput;
import pro.deta.orion.git.parser.wire.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestService;
import pro.deta.orion.git.parser.wire.error.GitGeneralException;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.INVALID_PROTOCOL_V2_FETCH_REQUEST;

class FetchContinuationTest {
    private static final String WANT =
            "1111111111111111111111111111111111111111";
    private static final String HAVE =
            "2222222222222222222222222222222222222222";

    @Test
    void parsesFragmentedBaseFetchRequest() {
        ByteBuf input = Unpooled.buffer();
        writeData(input, "thin-pack\n");
        writeData(input, "ofs-delta\n");
        writeData(input, "no-progress\n");
        writeData(input, "include-tag\n");
        writeData(input, "want " + WANT + "\n");
        writeData(input, "have " + HAVE + "\n");
        writeData(input, "done\n");
        writeFlush(input);

        Continuation<ByteBuf> completed = driveOneByteAtATime(input);

        assertThat(completed)
                .isInstanceOfSatisfying(
                        FetchResponseContinuation.class,
                        response -> {
                            NativeFetchRequest request = response.request();
                            assertThat(request.wants())
                                    .containsExactly(GitObjectId.of(WANT));
                            assertThat(request.haves())
                                    .containsExactly(GitObjectId.of(HAVE));
                            assertThat(request.done()).isTrue();
                            assertThat(request.thinPack()).isTrue();
                            assertThat(request.ofsDelta()).isTrue();
                            assertThat(request.includeTag()).isTrue();
                        });
    }

    @Test
    void acceptsMultipleWantsAndHavesWithoutLineFeeds() {
        String secondWant = "3".repeat(40);
        String secondHave = "4".repeat(40);
        ByteBuf input = Unpooled.buffer();
        writeData(input, "want " + WANT);
        writeData(input, "want " + secondWant);
        writeData(input, "have " + HAVE);
        writeData(input, "have " + secondHave);
        writeData(input, "done");
        writeFlush(input);

        FetchResponseContinuation response =
                (FetchResponseContinuation) drive(input);

        assertThat(response.request().wants())
                .containsExactly(
                        GitObjectId.of(WANT),
                        GitObjectId.of(secondWant));
        assertThat(response.request().haves())
                .containsExactly(
                        GitObjectId.of(HAVE),
                        GitObjectId.of(secondHave));
    }

    @Test
    void acceptsWaitForDoneNegotiationRequestWithoutDone() {
        ByteBuf input = Unpooled.buffer();
        writeData(input, "want " + WANT + "\n");
        writeData(input, "have " + HAVE + "\n");
        writeData(input, "wait-for-done\n");
        writeFlush(input);

        FetchNegotiationResponseContinuation response =
                (FetchNegotiationResponseContinuation) drive(input);

        assertThat(response.request().wants())
                .containsExactly(GitObjectId.of(WANT));
        assertThat(response.request().haves())
                .containsExactly(GitObjectId.of(HAVE));
        assertThat(response.request().done()).isFalse();
        assertThat(response.request().waitForDone()).isTrue();
    }

    @Test
    void writesNegotiationAcknowledgmentsAndAwaitsNextCommand() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.create("/demo.git")
                        .valueOrFailure("repository");
        GitObjectId have = repository.writeObject(
                ObjectType.BLOB,
                "have".getBytes(StandardCharsets.US_ASCII));
        ByteBuf outbound = outputBuffer();
        Driver driver = new Driver(
                GitMinimalWireMachine.testContext(
                        UnpooledByteBufAllocator.DEFAULT,
                        new GitNativeClientOutput(outbound),
                        provider,
                        GitNativeRepositoryAccessHook.ALLOW_ALL));
        ByteBuf input = Unpooled.buffer();
        writeData(input, "want " + WANT + "\n");
        writeData(input, "have " + have.value() + "\n");
        writeData(input, "wait-for-done\n");
        writeFlush(input);

        try {
            driver.drive(input);
            driver.drive(Unpooled.EMPTY_BUFFER);

            assertThat(driver.current)
                    .isInstanceOf(UploadCommandContinuation.class);
            assertThat(outbound.toString(StandardCharsets.US_ASCII))
                    .isEqualTo(
                            "0014acknowledgments\n"
                                    + "0031ACK " + have.value() + "\n"
                                    + "0000");
        } finally {
            input.release();
            outbound.release();
        }
    }

    @Test
    void rejectsMalformedOrIncompleteRequests() {
        assertInvalid(request("want invalid\n", "done\n"));
        assertInvalid(request("want " + WANT + " trailing\n", "done\n"));
        assertInvalid(request("want " + WANT.substring(1) + "\n", "done\n"));
        assertInvalid(request("have " + HAVE + "\n", "done\n"));
        assertInvalid(request(
                "want " + WANT + "\n",
                "done\n",
                "have " + HAVE + "\n"));
    }

    @Test
    void rejectsUnsupportedControlPackets() {
        assertInvalid(control("0001"));
        assertInvalid(control("0002"));
        assertInvalid(control("0004"));
    }

    private static void assertInvalid(ByteBuf input) {
        Continuation<ByteBuf> completed = drive(input);
        assertThat(completed)
                .isInstanceOfSatisfying(
                        Continuation.CompletedError.class,
                        error -> {
                            assertThat(error.message())
                                    .isEqualTo(
                                            INVALID_PROTOCOL_V2_FETCH_REQUEST
                                                    .getMessage());
                            assertThat(error.throwable())
                                    .isInstanceOf(GitGeneralException.class)
                                    .hasMessageContaining(
                                            INVALID_PROTOCOL_V2_FETCH_REQUEST
                                                    .name());
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

    private static ByteBuf request(String... lines) {
        ByteBuf input = Unpooled.buffer();
        for (String line : lines) {
            writeData(input, line);
        }
        writeFlush(input);
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

    private static InitialRequestData initialRequest() {
        return new InitialRequestData(
                InitialRequestService.UPLOAD_PACK,
                "/demo.git",
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

        private Driver() {
            this(defaultContext());
        }

        private Driver(GitMinimalWireMachine.Context context) {
            current = new FetchContinuation(
                    context,
                    initialRequest());
        }

        private void drive(ByteBuf input) {
            while (true) {
                ContinuationFlow<ByteBuf> flow =
                        current.process(input);
                if (flow instanceof
                        ContinuationFlow.Transition<ByteBuf> transition) {
                    current = transition.next();
                    if (current instanceof FetchResponseContinuation
                            || current instanceof
                            FetchNegotiationResponseContinuation
                            || current instanceof
                            UploadCommandContinuation
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

    private static GitMinimalWireMachine.Context defaultContext() {
        ByteBuf outbound = outputBuffer();
        return GitMinimalWireMachine.testContext(
                UnpooledByteBufAllocator.DEFAULT,
                new GitNativeClientOutput(outbound),
                new InMemoryNativeGitRepositoryProvider(),
                GitNativeRepositoryAccessHook.ALLOW_ALL);
    }
}
