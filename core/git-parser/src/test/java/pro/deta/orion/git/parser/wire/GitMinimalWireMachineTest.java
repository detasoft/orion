package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.wire.control.ControlState;
import pro.deta.orion.git.parser.wire.utils.RawSink;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitMinimalWireMachineTest {
    @Test
    void keepsOnlyContextAndPhaseAsMachineState() {
        List<String> fieldNames = new ArrayList<>();
        for (Field field : GitMinimalWireMachine.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                fieldNames.add(field.getName());
            }
        }

        assertThat(fieldNames).containsExactlyInAnyOrder("context", "phase");
    }

    @Test
    void deliversStructuredDataPayloadsWithoutCreatingRawSink() {
        RecordingWireHandlers handlers = new RecordingWireHandlers();
        try (GitMinimalWireMachine machine = machine(handlers)) {
            ByteBuf input = ascii("000bcommand0009value0000");
            assertThat(acceptAndRelease(machine, input)).isTrue();

            assertThat(input.refCnt()).isZero();
            assertControlPhase(machine, ControlState.ControlEmpty.INSTANCE);
            assertThat(handlers.controls).containsExactly(
                    new ControlState.ControlSuccess(ControlState.ControlType.DATA, 11),
                    new ControlState.ControlSuccess(ControlState.ControlType.DATA, 9),
                    new ControlState.ControlSuccess(ControlState.ControlType.FLUSH, 4));
            assertThat(handlers.structuredPayloads).containsExactly(asciiBytes("command"), asciiBytes("value"));
            assertThat(handlers.sinkCreations).hasValue(0);
        }
    }

    @Test
    void forwardsRawPackBytesAfterControlHandlerRequestsRawPayloadForwarding() {
        RecordingWireHandlers handlers = new RecordingWireHandlers();
        handlers.forwardRawAfterFlush = true;
        try (GitMinimalWireMachine machine = machine(handlers)) {
            ByteBuf input = asciiWithTail("0009cmd=10000", 'P', 'A', 'C', 'K', 0, 0, 1, 2);
            assertThat(acceptAndRelease(machine, input)).isTrue();

            assertThat(input.refCnt()).isZero();
            assertRawStreamPhase(machine, new ControlState.ControlSuccess(ControlState.ControlType.FLUSH, 4), true);
            assertThat(handlers.controls).containsExactly(
                    new ControlState.ControlSuccess(ControlState.ControlType.DATA, 9),
                    new ControlState.ControlSuccess(ControlState.ControlType.FLUSH, 4));
            assertThat(handlers.structuredPayloads).containsExactly(asciiBytes("cmd=1"));
            assertThat(handlers.sinkCreations).hasValue(1);
            assertThat(handlers.rawSink.controls)
                    .containsExactly(new ControlState.ControlSuccess(ControlState.ControlType.FLUSH, 4));
            assertThat(handlers.rawSink.chunks).containsExactly(new byte[]{'P', 'A', 'C', 'K', 0, 0, 1, 2});
        }
    }

    @Test
    void emitsCompletedControlEventsBeforeDeliveringStructuredDataPayloads() {
        RecordingWireHandlers handlers = new RecordingWireHandlers();
        try (GitMinimalWireMachine machine = machine(handlers)) {
            ByteBuf input = ascii("0012command=fetch\n00010010agent=orion\n0000");
            assertThat(acceptAndRelease(machine, input)).isTrue();

            assertThat(input.refCnt()).isZero();
            assertControlPhase(machine, ControlState.ControlEmpty.INSTANCE);
            assertThat(handlers.controls).containsExactly(
                    new ControlState.ControlSuccess(ControlState.ControlType.DATA, 18),
                    new ControlState.ControlSuccess(ControlState.ControlType.DELIMITER, 4),
                    new ControlState.ControlSuccess(ControlState.ControlType.DATA, 16),
                    new ControlState.ControlSuccess(ControlState.ControlType.FLUSH, 4));
            assertThat(handlers.structuredPayloads)
                    .containsExactly(asciiBytes("command=fetch\n"), asciiBytes("agent=orion\n"));
            assertThat(handlers.sinkCreations).hasValue(0);
        }
    }

    @Test
    void emitsSpecialControlEventsWithoutCreatingRawSink() {
        RecordingWireHandlers handlers = new RecordingWireHandlers();
        try (GitMinimalWireMachine machine = machine(handlers)) {
            ByteBuf input = ascii("000000010002");
            assertThat(acceptAndRelease(machine, input)).isTrue();

            assertThat(input.refCnt()).isZero();
            assertControlPhase(machine, ControlState.ControlEmpty.INSTANCE);
            assertThat(handlers.controls).containsExactly(
                    new ControlState.ControlSuccess(ControlState.ControlType.FLUSH, 4),
                    new ControlState.ControlSuccess(ControlState.ControlType.DELIMITER, 4),
                    new ControlState.ControlSuccess(ControlState.ControlType.RESPONSE_END, 4));
            assertThat(handlers.structuredPayloads).isEmpty();
            assertThat(handlers.sinkCreations).hasValue(0);
        }
    }

    @Test
    void deliversEmptyStructuredDataPayloadWithoutWaitingForMoreInput() {
        RecordingWireHandlers handlers = new RecordingWireHandlers();
        try (GitMinimalWireMachine machine = machine(handlers)) {
            ByteBuf input = ascii("0004");
            assertThat(acceptAndRelease(machine, input)).isTrue();

            assertThat(input.refCnt()).isZero();
            assertControlPhase(machine, ControlState.ControlEmpty.INSTANCE);
            assertThat(handlers.controls).containsExactly(
                    new ControlState.ControlSuccess(ControlState.ControlType.DATA, 4));
            assertThat(handlers.structuredPayloads).containsExactly(new byte[0]);
            assertThat(handlers.sinkCreations).hasValue(0);
        }
    }

    @Test
    void closeFailsWhenHeaderIsIncomplete() {
        GitMinimalWireMachine machine = machine(new RecordingWireHandlers());
        ByteBuf input = ascii("00");

        assertThat(acceptAndRelease(machine, input)).isTrue();
        assertThat(input.refCnt()).isZero();

        assertThatThrownBy(machine::close)
                .isInstanceOfSatisfying(GitWireException.class, error -> assertThat(error.error())
                        .isEqualTo(new GitWireError(
                                GitWireError.Kind.INCOMPLETE_HEADER,
                                GitWireError.Phase.CONTROL_HEADER,
                                0,
                                0,
                                "Incomplete Git pkt-line header")));
    }

    @Test
    void closeFailsWhenPayloadIsIncomplete() {
        GitMinimalWireMachine machine = machine(new RecordingWireHandlers());
        ByteBuf input = ascii("000aabc");

        assertThat(acceptAndRelease(machine, input)).isTrue();
        assertThat(input.refCnt()).isZero();

        assertThatThrownBy(machine::close)
                .isInstanceOfSatisfying(GitWireException.class, error -> assertThat(error.error())
                        .isEqualTo(new GitWireError(
                                GitWireError.Kind.INCOMPLETE_PAYLOAD,
                                GitWireError.Phase.STRUCTURED_PAYLOAD,
                                0,
                                0,
                                "Incomplete Git pkt-line payload")));
    }

    @Test
    void invalidHeaderFailsWithTypedWireError() {
        GitMinimalWireMachine machine = machine(new RecordingWireHandlers());
        ByteBuf input = ascii("zzzz");

        try {
            assertThatThrownBy(() -> machine.accept(input))
                    .isInstanceOfSatisfying(GitWireException.class, error -> assertThat(error.error())
                            .isEqualTo(new GitWireError(
                                    GitWireError.Kind.INVALID_HEX_HEADER,
                                    GitWireError.Phase.CONTROL_HEADER,
                                    0,
                                    0,
                                    "Pkt-line length contains non-hex byte")));
        } finally {
            input.release();
            machine.close();
        }
    }

    @Test
    void invalidHeaderAfterCompletedPacketReportsPacketPosition() {
        GitMinimalWireMachine machine = machine(new RecordingWireHandlers());
        ByteBuf input = ascii("0007onezzzz");

        try {
            assertThatThrownBy(() -> machine.accept(input))
                    .isInstanceOfSatisfying(GitWireException.class, error -> assertThat(error.error())
                            .isEqualTo(new GitWireError(
                                    GitWireError.Kind.INVALID_HEX_HEADER,
                                    GitWireError.Phase.CONTROL_HEADER,
                                    1,
                                    7,
                                    "Pkt-line length contains non-hex byte")));
        } finally {
            input.release();
            machine.close();
        }
    }

    @Test
    void createsRawSinkOnlyWhenRawStreamBytesArriveAfterFlushControl() {
        CountingByteBufAllocator allocator = new CountingByteBufAllocator();
        RecordingWireHandlers handlers = new RecordingWireHandlers();
        handlers.forwardRawAfterFlush = true;
        try (GitMinimalWireMachine machine = machine(allocator, handlers)) {
            assertThat(allocator.allocations()).isZero();

            ByteBuf controlOnly = ascii("0000");
            assertThat(acceptAndRelease(machine, controlOnly)).isTrue();
            assertThat(controlOnly.refCnt()).isZero();
            assertThat(allocator.allocations()).isZero();
            assertRawStreamPhase(machine, new ControlState.ControlSuccess(ControlState.ControlType.FLUSH, 4), false);
            assertThat(handlers.sinkCreations).hasValue(0);

            ByteBuf raw = buffer(10, 11, 12);
            assertThat(acceptAndRelease(machine, raw)).isTrue();
            assertThat(raw.refCnt()).isZero();
            assertThat(allocator.allocations()).isZero();
            assertRawStreamPhase(machine, new ControlState.ControlSuccess(ControlState.ControlType.FLUSH, 4), true);
            assertThat(handlers.sinkCreations).hasValue(1);
            assertThat(handlers.rawSink.controls)
                    .containsExactly(new ControlState.ControlSuccess(ControlState.ControlType.FLUSH, 4));
            assertThat(handlers.rawSink.chunks).containsExactly(new byte[]{10, 11, 12});

            ByteBuf remaining = buffer(13, 14, 15);
            assertThat(acceptAndRelease(machine, remaining)).isTrue();
            assertThat(remaining.refCnt()).isZero();
            assertRawStreamPhase(machine, new ControlState.ControlSuccess(ControlState.ControlType.FLUSH, 4), true);
            assertThat(handlers.sinkCreations).hasValue(1);
            assertThat(handlers.rawSink.chunks).containsExactly(new byte[]{10, 11, 12}, new byte[]{13, 14, 15});
        }
    }

    @Test
    void closeFailsAfterCompleteDataControlWhenStructuredPayloadNeverArrives() {
        RecordingWireHandlers handlers = new RecordingWireHandlers();
        ByteBuf controlOnly = ascii("000a");
        GitMinimalWireMachine machine = machine(handlers);
        try {
            assertThat(acceptAndRelease(machine, controlOnly)).isTrue();
            assertThat(controlOnly.refCnt()).isZero();
            assertThat(handlers.structuredPayloads).isEmpty();
            assertThatThrownBy(machine::close)
                    .isInstanceOfSatisfying(GitWireException.class, error -> assertThat(error.error().kind())
                            .isEqualTo(GitWireError.Kind.INCOMPLETE_PAYLOAD));
            assertThat(handlers.sinkCreations).hasValue(0);
        } finally {
            machine.close();
        }
    }

    @Test
    void deliversStructuredPayloadAcrossMultipleInputChunks() {
        RecordingWireHandlers handlers = new RecordingWireHandlers();
        try (GitMinimalWireMachine machine = machine(handlers)) {
            ByteBuf input = asciiWithTail("000a", 'a', 'b');
            assertThat(acceptAndRelease(machine, input)).isTrue();

            assertThat(input.refCnt()).isZero();
            assertStructuredPhase(machine, new ControlState.ControlSuccess(ControlState.ControlType.DATA, 10), 4);
            assertThat(handlers.structuredPayloads).isEmpty();

            ByteBuf remaining = ascii("cdef");
            assertThat(acceptAndRelease(machine, remaining)).isTrue();
            assertThat(remaining.refCnt()).isZero();
            assertControlPhase(machine, ControlState.ControlEmpty.INSTANCE);
            assertThat(handlers.structuredPayloads).containsExactly(asciiBytes("abcdef"));
            assertThat(handlers.sinkCreations).hasValue(0);
        }
    }

    @Test
    void deliversOnlyDeclaredStructuredPayloadBeforeReadingNextPktLineInSameInput() {
        RecordingWireHandlers handlers = new RecordingWireHandlers();
        try (GitMinimalWireMachine machine = machine(handlers)) {
            ByteBuf input = ascii("000aabcdef0007xyz");
            assertThat(acceptAndRelease(machine, input)).isTrue();

            assertThat(input.refCnt()).isZero();
            assertControlPhase(machine, ControlState.ControlEmpty.INSTANCE);
            assertThat(handlers.controls).containsExactly(
                    new ControlState.ControlSuccess(ControlState.ControlType.DATA, 10),
                    new ControlState.ControlSuccess(ControlState.ControlType.DATA, 7));
            assertThat(handlers.structuredPayloads).containsExactly(asciiBytes("abcdef"), asciiBytes("xyz"));
            assertThat(handlers.sinkCreations).hasValue(0);
        }
    }

    @Test
    void skipsSpecialPktLineBeforeReadingNextPktLineInSameInput() {
        RecordingWireHandlers handlers = new RecordingWireHandlers();
        try (GitMinimalWireMachine machine = machine(handlers)) {
            ByteBuf input = ascii("00000007xyz");
            assertThat(acceptAndRelease(machine, input)).isTrue();

            assertThat(input.refCnt()).isZero();
            assertControlPhase(machine, ControlState.ControlEmpty.INSTANCE);
            assertThat(handlers.controls).containsExactly(
                    new ControlState.ControlSuccess(ControlState.ControlType.FLUSH, 4),
                    new ControlState.ControlSuccess(ControlState.ControlType.DATA, 7));
            assertThat(handlers.structuredPayloads).containsExactly(asciiBytes("xyz"));
            assertThat(handlers.sinkCreations).hasValue(0);
        }
    }

    @Test
    void deliversStructuredPayloadWhenFragmentedHeaderCompletesWithPayloadTail() {
        CountingByteBufAllocator allocator = new CountingByteBufAllocator();
        RecordingWireHandlers handlers = new RecordingWireHandlers();
        try (GitMinimalWireMachine machine = machine(allocator, handlers)) {
            assertThat(allocator.allocations()).isZero();

            ByteBuf first = ascii("00");
            assertThat(acceptAndRelease(machine, first)).isTrue();
            assertThat(first.refCnt()).isZero();
            assertThat(allocator.allocations()).isOne();
            assertThat(allocator.lastInitialCapacity()).isEqualTo(2);
            assertThat(allocator.lastMaxCapacity()).isEqualTo(4);
            assertThat(machine.state().phase()).isInstanceOfSatisfying(
                    GitMinimalWireMachine.ControlPhase.class,
                    phase -> assertThat(phase.state()).isInstanceOf(ControlState.MoreDataNeeded.class));
            assertThat(handlers.structuredPayloads).isEmpty();

            ByteBuf second = ascii("0aabcdef");
            assertThat(acceptAndRelease(machine, second)).isTrue();
            assertThat(second.refCnt()).isZero();
            assertThat(allocator.allocations()).isOne();
            assertControlPhase(machine, ControlState.ControlEmpty.INSTANCE);
            assertThat(handlers.controls).containsExactly(new ControlState.ControlSuccess(ControlState.ControlType.DATA, 10));
            assertThat(handlers.structuredPayloads).containsExactly(asciiBytes("abcdef"));
            assertThat(handlers.sinkCreations).hasValue(0);
        }
    }

    @Test
    void passesUploadPackWantPktLineInSingleInputChunk() {
        RecordingWireHandlers handlers = new RecordingWireHandlers();
        String wantPacket = "0032want 0123456789012345678901234567890123456789\n";
        try (GitMinimalWireMachine machine = machine(handlers)) {
            ByteBuf input = ascii(wantPacket);
            assertThat(acceptAndRelease(machine, input)).isTrue();

            assertThat(input.refCnt()).isZero();
            assertControlPhase(machine, ControlState.ControlEmpty.INSTANCE);
            assertThat(handlers.controls).containsExactly(new ControlState.ControlSuccess(ControlState.ControlType.DATA, 50));
            assertThat(handlers.structuredPayloads).containsExactly(asciiBytes(wantPacket.substring(4)));
            assertThat(handlers.sinkCreations).hasValue(0);
        }
    }

    @Test
    void passesUploadPackWantPktLineAcrossMultipleInputChunks() {
        RecordingWireHandlers handlers = new RecordingWireHandlers();
        String wantPacket = "0032want 0123456789012345678901234567890123456789\n";
        try (GitMinimalWireMachine machine = machine(handlers)) {
            ByteBuf first = ascii("00");
            assertThat(acceptAndRelease(machine, first)).isTrue();
            assertThat(first.refCnt()).isZero();
            assertThat(machine.state().phase()).isInstanceOfSatisfying(
                    GitMinimalWireMachine.ControlPhase.class,
                    phase -> assertThat(phase.state()).isInstanceOf(ControlState.MoreDataNeeded.class));
            assertThat(handlers.structuredPayloads).isEmpty();

            ByteBuf second = ascii("32");
            assertThat(acceptAndRelease(machine, second)).isTrue();
            assertThat(second.refCnt()).isZero();
            assertStructuredPhase(machine, new ControlState.ControlSuccess(ControlState.ControlType.DATA, 50), 46);
            assertThat(handlers.structuredPayloads).isEmpty();

            ByteBuf third = ascii(wantPacket.substring(4));
            assertThat(acceptAndRelease(machine, third)).isTrue();
            assertThat(third.refCnt()).isZero();
            assertControlPhase(machine, ControlState.ControlEmpty.INSTANCE);
            assertThat(handlers.controls).containsExactly(new ControlState.ControlSuccess(ControlState.ControlType.DATA, 50));
            assertThat(handlers.structuredPayloads).containsExactly(asciiBytes(wantPacket.substring(4)));
            assertThat(handlers.sinkCreations).hasValue(0);
        }
    }

    @Test
    void semanticPhasePassesACompletedValueThroughTheMachineStack() {
        GitMinimalWireMachine.SemanticPhase terminalPhase = (control, payload, values) -> {
            assertThat(control.type()).isEqualTo(ControlState.ControlType.FLUSH);
            return new GitMinimalWireMachine.SemanticTransition.Complete<>(
                    String.class,
                    values.pop(String.class) + "-complete");
        };
        GitMinimalWireMachine.SemanticPhase valuePhase = (control, payload, values) -> {
            values.push(String.class, payload.toString(StandardCharsets.UTF_8));
            return new GitMinimalWireMachine.SemanticTransition.Next(terminalPhase);
        };
        try (GitMinimalWireMachine machine = semanticMachine(String.class, valuePhase)) {
            ByteBuf input = ascii("0009value0000");

            assertThat(acceptAndRelease(machine, input)).isTrue();

            assertThat(machine.outcome(String.class))
                    .contains(new GitWireOutcome.Success<>("value-complete"));
            assertThat(machine.result(String.class)).isEqualTo("value-complete");
            assertThat(machine.state().phase()).isInstanceOf(GitMinimalWireMachine.CompletedPhase.class);
        }
    }

    @Test
    void semanticFailureIsRetainedAsATerminalMachineOutcome() {
        GitWireError expected = new GitWireError(
                GitWireError.Kind.INVALID_PROTOCOL_V2_REQUEST,
                GitWireError.Phase.STRUCTURED_PAYLOAD,
                0,
                4,
                "bad semantic value");
        GitMinimalWireMachine.SemanticPhase failingPhase = (_control, _payload, _values) -> {
            throw new GitWireException(expected);
        };
        try (GitMinimalWireMachine machine = semanticMachine(String.class, failingPhase)) {
            ByteBuf input = ascii("0009value");

            assertThat(acceptAndRelease(machine, input)).isTrue();

            assertThat(machine.outcome(String.class))
                    .contains(new GitWireOutcome.Failure<>(new GitWireFailure(expected)));
            assertThat(machine.state().phase()).isInstanceOf(GitMinimalWireMachine.FailedPhase.class);
            assertThatThrownBy(() -> machine.result(String.class))
                    .isInstanceOfSatisfying(GitWireException.class, error -> assertThat(error.error())
                            .isEqualTo(expected));
        }
    }

    @Test
    void semanticResultIsUnavailableBeforeTerminalTransition() {
        GitMinimalWireMachine.SemanticPhase waitingPhase =
                (_control, _payload, _values) -> new GitMinimalWireMachine.SemanticTransition.Next(
                        (_nextControl, _nextPayload, _nextValues) ->
                                new GitMinimalWireMachine.SemanticTransition.Complete<>(String.class, "done"));
        try (GitMinimalWireMachine machine = semanticMachine(String.class, waitingPhase)) {
            ByteBuf input = ascii("0009value");

            assertThat(acceptAndRelease(machine, input)).isTrue();

            assertThat(machine.outcome(String.class)).isEqualTo(Optional.empty());
            assertThatThrownBy(() -> machine.result(String.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not complete");
        }
    }

    private static GitMinimalWireMachine machine(RecordingWireHandlers handlers) {
        return machine(UnpooledByteBufAllocator.DEFAULT, handlers);
    }

    private static GitMinimalWireMachine machine(ByteBufAllocator allocator, RecordingWireHandlers handlers) {
        return new GitMinimalWireMachine(allocator, handlers, handlers, handlers);
    }

    private static <T> GitMinimalWireMachine semanticMachine(
            Class<T> resultType,
            GitMinimalWireMachine.SemanticPhase initialPhase) {
        return new GitMinimalWireMachine(
                UnpooledByteBufAllocator.DEFAULT,
                resultType,
                initialPhase,
                _control -> new RecordingRawSink());
    }

    private static void assertControlPhase(
            GitMinimalWireMachine machine,
            ControlState expectedState) {
        assertThat(machine.state().phase()).isInstanceOfSatisfying(
                GitMinimalWireMachine.ControlPhase.class,
                phase -> assertThat(phase.state()).isEqualTo(expectedState));
    }

    private static void assertStructuredPhase(
            GitMinimalWireMachine machine,
            ControlState.ControlSuccess control,
            int remaining) {
        assertThat(machine.state().phase()).isInstanceOfSatisfying(
                GitMinimalWireMachine.StructuredPayloadPhase.class,
                phase -> {
                    assertThat(phase.control()).isEqualTo(control);
                    assertThat(phase.remaining()).isEqualTo(remaining);
                });
    }

    private static void assertRawStreamPhase(
            GitMinimalWireMachine machine,
            ControlState.ControlSuccess control,
            boolean targetCreated) {
        assertThat(machine.state().phase()).isInstanceOfSatisfying(
                GitMinimalWireMachine.RawStreamPhase.class,
                phase -> {
                    assertThat(phase.control()).isEqualTo(control);
                    assertThat(phase.targetCreated()).isEqualTo(targetCreated);
                });
    }

    private static boolean acceptAndRelease(GitMinimalWireMachine machine, ByteBuf input) {
        boolean releaseInput = machine.accept(input);
        if (releaseInput) {
            input.release();
        }
        return releaseInput;
    }

    private static ByteBuf buffer(int... values) {
        ByteBuf buffer = Unpooled.buffer(values.length);
        for (int value : values) {
            buffer.writeByte(value);
        }
        return buffer;
    }

    private static ByteBuf ascii(String value) {
        ByteBuf buffer = Unpooled.buffer(value.length());
        for (int i = 0; i < value.length(); i++) {
            buffer.writeByte(value.charAt(i));
        }
        return buffer;
    }

    private static ByteBuf asciiWithTail(String value, int... tail) {
        ByteBuf buffer = Unpooled.buffer(value.length() + tail.length);
        for (int i = 0; i < value.length(); i++) {
            buffer.writeByte(value.charAt(i));
        }
        for (int valueByte : tail) {
            buffer.writeByte(valueByte);
        }
        return buffer;
    }

    private static byte[] asciiBytes(String value) {
        byte[] bytes = new byte[value.length()];
        for (int i = 0; i < value.length(); i++) {
            bytes[i] = (byte) value.charAt(i);
        }
        return bytes;
    }

    private static byte[] readAndRelease(ByteBuf input) {
        try {
            byte[] bytes = new byte[input.readableBytes()];
            input.readBytes(bytes);
            return bytes;
        } finally {
            input.release();
        }
    }

    private static final class RecordingWireHandlers implements
            GitMinimalWireMachine.FrameConsumer,
            GitMinimalWireMachine.StructuredPayloadConsumer,
            GitMinimalWireMachine.RawTargetFactory {
        private final List<ControlState.ControlSuccess> controls = new ArrayList<>();
        private final List<byte[]> structuredPayloads = new ArrayList<>();
        private final AtomicInteger sinkCreations = new AtomicInteger();
        private final RecordingRawSink rawSink = new RecordingRawSink();
        private boolean forwardRawAfterFlush;

        @Override
        public void accept(ControlState.ControlSuccess control, GitMinimalWireMachine.FlowControl flow) {
            controls.add(control);
            if (forwardRawAfterFlush && control.type() == ControlState.ControlType.FLUSH) {
                flow.forwardRawPayload();
            }
        }

        @Override
        public void accept(ControlState.ControlSuccess control, ByteBuf payload) {
            structuredPayloads.add(readAndRelease(payload));
        }

        @Override
        public RawSink.Target create(ControlState.ControlSuccess control) {
            sinkCreations.incrementAndGet();
            rawSink.controls.add(control);
            return rawSink;
        }
    }

    private static final class RecordingRawSink implements RawSink.Target {
        private final List<ControlState.ControlSuccess> controls = new ArrayList<>();
        private final List<byte[]> chunks = new ArrayList<>();

        @Override
        public void accept(ByteBuf input) {
            try {
                byte[] bytes = new byte[input.readableBytes()];
                input.readBytes(bytes);
                chunks.add(bytes);
            } finally {
                input.release();
            }
        }
    }
}
