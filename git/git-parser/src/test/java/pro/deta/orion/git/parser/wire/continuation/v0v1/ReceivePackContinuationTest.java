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
import pro.deta.orion.git.parser.wire.continuation.exchange.LegacyReceiveCommand;
import pro.deta.orion.git.parser.wire.continuation.exchange.LegacyReceiveCommandSection;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReceivePackContinuationTest {
    private static final String NULL_ID = "0".repeat(40);
    private static final String FIRST_ID = "1".repeat(40);
    private static final String SECOND_ID = "2".repeat(40);

    @Test
    void advertisesThenParsesFragmentedCreateCommand() {
        ByteBuf outbound = outputBuffer();
        try {
            ReceiveCommandContinuation commands =
                    advertisedCommands(outbound);
            byte[] request = commandRequest(
                    NULL_ID
                            + " "
                            + FIRST_ID
                            + " refs/heads/main\0report-status side-band-64k\n");

            Continuation<ByteBuf> current = commands;
            for (byte value : request) {
                ByteBuf fragment = Unpooled.wrappedBuffer(
                        new byte[] {value});
                try {
                    current = driveUntilAwaitOrBoundary(
                            current,
                            fragment);
                } finally {
                    fragment.release();
                }
            }

            assertThat(current)
                    .isInstanceOf(ReceivePackBoundaryContinuation.class);
            LegacyReceiveCommandSection section =
                    ((ReceivePackBoundaryContinuation) current)
                            .commandSection();
            assertThat(section.commands())
                    .singleElement()
                    .satisfies(command -> {
                        assertThat(command.oldObjectId().value())
                                .isEqualTo(NULL_ID);
                        assertThat(command.newObjectId().value())
                                .isEqualTo(FIRST_ID);
                        assertThat(command.refName())
                                .isEqualTo("refs/heads/main");
                        assertThat(command.type())
                                .isEqualTo(
                                        LegacyReceiveCommand.Type.CREATE);
                    });
            assertThat(section.capabilities())
                    .containsExactly(
                            "report-status",
                            "side-band-64k");
            assertThat(section.requiresPack()).isTrue();
        } finally {
            outbound.release();
        }
    }

    @Test
    void parsesMultipleCommandsAndLeavesPackPrefixUnread() {
        ByteBuf outbound = outputBuffer();
        ByteBuf input = Unpooled.buffer();
        try {
            ReceiveCommandContinuation commands =
                    advertisedCommands(outbound);
            writeData(
                    input,
                    FIRST_ID
                            + " "
                            + SECOND_ID
                            + " refs/heads/main\0report-status\n");
            writeData(
                    input,
                    SECOND_ID
                            + " "
                            + NULL_ID
                            + " refs/heads/old\n");
            input.writeCharSequence(
                    "0000PACK",
                    StandardCharsets.US_ASCII);

            Continuation<ByteBuf> current =
                    driveUntilAwaitOrBoundary(commands, input);

            assertThat(current)
                    .isInstanceOf(ReceivePackBoundaryContinuation.class);
            LegacyReceiveCommandSection section =
                    ((ReceivePackBoundaryContinuation) current)
                            .commandSection();
            assertThat(section.commands())
                    .extracting(
                            LegacyReceiveCommand::type,
                            LegacyReceiveCommand::refName)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(
                                    LegacyReceiveCommand.Type.UPDATE,
                                    "refs/heads/main"),
                            org.assertj.core.groups.Tuple.tuple(
                                    LegacyReceiveCommand.Type.DELETE,
                                    "refs/heads/old"));
            assertThat(section.requiresPack()).isTrue();
            assertThat(input.toString(StandardCharsets.US_ASCII))
                    .isEqualTo("PACK");
        } finally {
            input.release();
            outbound.release();
        }
    }

    @Test
    void allDeleteCommandsDoNotRequirePack() {
        ByteBuf outbound = outputBuffer();
        ByteBuf input = Unpooled.buffer();
        try {
            ReceiveCommandContinuation commands =
                    advertisedCommands(outbound);
            writeData(
                    input,
                    FIRST_ID
                            + " "
                            + NULL_ID
                            + " refs/heads/main\0report-status\n");
            input.writeCharSequence("0000", StandardCharsets.US_ASCII);

            Continuation<ByteBuf> current =
                    driveUntilAwaitOrBoundary(commands, input);

            LegacyReceiveCommandSection section =
                    ((ReceivePackBoundaryContinuation) current)
                            .commandSection();
            assertThat(section.requiresPack()).isFalse();
        } finally {
            input.release();
            outbound.release();
        }
    }

    @Test
    void rejectsDuplicateRefs() {
        assertCommandFailure(
                List.of(
                        NULL_ID
                                + " "
                                + FIRST_ID
                                + " refs/heads/main\0report-status\n",
                        FIRST_ID
                                + " "
                                + SECOND_ID
                                + " refs/heads/main\n"),
                "duplicate ref");
    }

    @Test
    void rejectsCapabilitiesAfterFirstCommand() {
        assertCommandFailure(
                List.of(
                        NULL_ID
                                + " "
                                + FIRST_ID
                                + " refs/heads/main\n",
                        NULL_ID
                                + " "
                                + SECOND_ID
                                + " refs/heads/feature\0report-status\n"),
                "only on the first command");
    }

    @Test
    void rejectsMalformedObjectId() {
        assertCommandFailure(
                List.of(
                        "invalid "
                                + FIRST_ID
                                + " refs/heads/main\n"),
                "40-digit hexadecimal");
    }

    @Test
    void rejectsFlushBeforeCommand() {
        ByteBuf outbound = outputBuffer();
        ByteBuf input = Unpooled.copiedBuffer(
                "0000",
                StandardCharsets.US_ASCII);
        try {
            Continuation<ByteBuf> current = driveToTerminalStep(
                    advertisedCommands(outbound),
                    input);

            assertThat(current)
                    .isInstanceOfSatisfying(
                            Continuation.CompletedError.class,
                            error -> assertThat(error.message())
                                    .contains("before the first command"));
        } finally {
            input.release();
            outbound.release();
        }
    }

    @Test
    void reportsOutputOperationFailureThroughContinuationFlow() {
        ByteBuf outbound = outputBuffer();
        GitNativeClientOutput output = new GitNativeClientOutput(
                new SubmittedByteBufOutput(outbound, ignored -> {
                    throw new IllegalStateException("delivery failed");
                }));
        ByteBuf input = Unpooled.buffer();
        try {
            ContinuationFlow<ByteBuf> flow = continuation(output)
                    .process(input);

            assertThat(flow)
                    .isInstanceOf(ContinuationFlow.Transition.class);
            assertThat(((ContinuationFlow.Transition<?>) flow).next())
                    .isInstanceOfSatisfying(
                            Continuation.CompletedError.class,
                            error -> {
                                assertThat(error.message()).isEqualTo(
                                        "Failed to advertise native Git"
                                                + " repository for receive-pack");
                                assertThat(error.throwable())
                                        .hasMessage(
                                                "delivery failed");
                            });
        } finally {
            input.release();
            outbound.release();
        }
    }

    private static void assertCommandFailure(
            List<String> commandLines,
            String message) {
        ByteBuf outbound = outputBuffer();
        ByteBuf input = Unpooled.buffer();
        try {
            for (String commandLine : commandLines) {
                writeData(input, commandLine);
            }
            Continuation<ByteBuf> current = driveToTerminalStep(
                    advertisedCommands(outbound),
                    input);

            assertThat(current)
                    .isInstanceOfSatisfying(
                            Continuation.CompletedError.class,
                            error -> assertThat(error.message())
                                    .contains(message));
        } finally {
            input.release();
            outbound.release();
        }
    }

    private static ReceiveCommandContinuation advertisedCommands(
            ByteBuf outbound) {
        ContinuationFlow<ByteBuf> flow = continuation(
                new GitNativeClientOutput(new RecordingBufferedByteOutput(outbound)))
                .process(Unpooled.EMPTY_BUFFER);
        assertThat(flow)
                .isInstanceOf(ContinuationFlow.Transition.class);
        assertThat(outbound.toString(StandardCharsets.US_ASCII))
                .contains("refs/heads/main")
                .endsWith("0000");
        return (ReceiveCommandContinuation)
                ((ContinuationFlow.Transition<ByteBuf>) flow).next();
    }

    private static ReceivePackContinuation continuation(
            GitNativeClientOutput output) {
        GitMinimalWireMachine.Context context =
                GitMinimalWireMachine.testContext(
                        UnpooledByteBufAllocator.DEFAULT,
                        output,
                        new InMemoryNativeGitRepositoryProvider(),
                        GitNativeRepositoryAccessHook.ALLOW_ALL);
        return new ReceivePackContinuation(
                context,
                request(),
                advertisement());
    }

    private static GitV1Advertisement advertisement() {
        return new GitV1Advertisement(
                List.of(),
                List.of(GitAdvertisedRef.direct(
                        FIRST_ID,
                        "refs/heads/main")));
    }

    private static InitialRequestData request() {
        return new InitialRequestData(
                InitialRequestService.RECEIVE_PACK,
                "/demo.git",
                "localhost",
                Map.of());
    }

    private static byte[] commandRequest(String commandLine) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            writeData(buffer, commandLine);
            buffer.writeCharSequence(
                    "0000",
                    StandardCharsets.US_ASCII);
            byte[] request = new byte[buffer.readableBytes()];
            buffer.readBytes(request);
            return request;
        } finally {
            buffer.release();
        }
    }

    private static void writeData(
            ByteBuf output,
            String value) {
        byte[] payload = value.getBytes(StandardCharsets.US_ASCII);
        output.writeCharSequence(
                "%04x".formatted(payload.length + 4),
                StandardCharsets.US_ASCII);
        output.writeBytes(payload);
    }

    private static Continuation<ByteBuf> driveUntilAwaitOrBoundary(
            Continuation<ByteBuf> initial,
            ByteBuf input) {
        Continuation<ByteBuf> current = initial;
        while (!(current instanceof ReceivePackBoundaryContinuation)
                && !current.terminal()) {
            ContinuationFlow<ByteBuf> flow = current.process(input);
            if (flow instanceof ContinuationFlow.Await<ByteBuf>) {
                return current;
            }
            assertThat(flow)
                    .isInstanceOf(ContinuationFlow.Transition.class);
            current = ((ContinuationFlow.Transition<ByteBuf>) flow).next();
        }
        return current;
    }

    private static Continuation<ByteBuf> driveToTerminalStep(
            Continuation<ByteBuf> initial,
            ByteBuf input) {
        Continuation<ByteBuf> current = initial;
        while (!current.terminal()) {
            ContinuationFlow<ByteBuf> flow = current.process(input);
            if (flow instanceof ContinuationFlow.Await<ByteBuf>) {
                return current;
            }
            current = ((ContinuationFlow.Transition<ByteBuf>) flow).next();
        }
        return current;
    }

    private static ByteBuf outputBuffer() {
        return Unpooled.buffer(
                GitNativeClientOutput.BUFFER_CAPACITY,
                GitNativeClientOutput.BUFFER_CAPACITY);
    }
}
