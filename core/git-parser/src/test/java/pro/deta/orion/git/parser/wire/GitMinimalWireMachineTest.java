package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.wire.control.ControlState;
import pro.deta.orion.git.parser.wire.utils.RawSink;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitMinimalWireMachineTest {
    @Test
    void emitsCompletedControlEventsBeforeForwardingDataPayloads() {
        RecordingRawSink rawSink = new RecordingRawSink();
        List<ControlState.ControlSuccess> controls = new ArrayList<>();
        AtomicInteger sinkCreations = new AtomicInteger();
        try (GitMinimalWireMachine machine = new GitMinimalWireMachine(
                UnpooledByteBufAllocator.DEFAULT,
                controls::add,
                control -> {
                    sinkCreations.incrementAndGet();
                    rawSink.controls.add(control);
                    return rawSink;
                })) {
            ByteBuf input = ascii("0012command=fetch\n00010010agent=orion\n0000");
            assertThat(acceptAndRelease(machine, input)).isTrue();

            assertThat(input.refCnt()).isZero();
            assertControlPhase(machine, ControlState.ControlEmpty.INSTANCE);
            assertThat(controls).containsExactly(
                    new ControlState.ControlSuccess(ControlState.ControlType.DATA, 18),
                    new ControlState.ControlSuccess(ControlState.ControlType.DELIMITER, 4),
                    new ControlState.ControlSuccess(ControlState.ControlType.DATA, 16),
                    new ControlState.ControlSuccess(ControlState.ControlType.FLUSH, 4));
            assertThat(sinkCreations).hasValue(2);
            assertThat(rawSink.chunks).containsExactly(asciiBytes("command=fetch\n"), asciiBytes("agent=orion\n"));
        }
    }

    @Test
    void emitsSpecialControlEventsWithoutCreatingRawSink() {
        List<ControlState.ControlSuccess> controls = new ArrayList<>();
        AtomicInteger sinkCreations = new AtomicInteger();
        try (GitMinimalWireMachine machine = new GitMinimalWireMachine(
                UnpooledByteBufAllocator.DEFAULT,
                controls::add,
                control -> {
                    sinkCreations.incrementAndGet();
                    return new RecordingRawSink();
                })) {
            ByteBuf input = ascii("000000010002");
            assertThat(acceptAndRelease(machine, input)).isTrue();

            assertThat(input.refCnt()).isZero();
            assertControlPhase(machine, ControlState.ControlEmpty.INSTANCE);
            assertThat(controls).containsExactly(
                    new ControlState.ControlSuccess(ControlState.ControlType.FLUSH, 4),
                    new ControlState.ControlSuccess(ControlState.ControlType.DELIMITER, 4),
                    new ControlState.ControlSuccess(ControlState.ControlType.RESPONSE_END, 4));
            assertThat(sinkCreations).hasValue(0);
        }
    }

    @Test
    void closeFailsWhenHeaderIsIncomplete() {
        GitMinimalWireMachine machine = new GitMinimalWireMachine(
                UnpooledByteBufAllocator.DEFAULT,
                control -> new RecordingRawSink());
        ByteBuf input = ascii("00");

        assertThat(acceptAndRelease(machine, input)).isTrue();
        assertThat(input.refCnt()).isZero();

        assertThatThrownBy(machine::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Incomplete Git pkt-line header");
    }

    @Test
    void closeFailsWhenPayloadIsIncomplete() {
        GitMinimalWireMachine machine = new GitMinimalWireMachine(
                UnpooledByteBufAllocator.DEFAULT,
                control -> new RecordingRawSink());
        ByteBuf input = ascii("000aabc");

        assertThat(acceptAndRelease(machine, input)).isTrue();
        assertThat(input.refCnt()).isZero();

        assertThatThrownBy(machine::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Incomplete Git pkt-line payload");
    }

    @Test
    void createsRawSinkOnlyWhenRawBytesArriveAfterControl() {
        CountingByteBufAllocator allocator = new CountingByteBufAllocator();
        AtomicInteger sinkCreations = new AtomicInteger();
        RecordingRawSink rawSink = new RecordingRawSink();
        try (GitMinimalWireMachine machine = new GitMinimalWireMachine(
                allocator,
                control -> {
                    sinkCreations.incrementAndGet();
                    rawSink.controls.add(control);
                    return rawSink;
                })) {
            assertThat(allocator.allocations()).isZero();

            ByteBuf controlOnly = ascii("000a");
            assertThat(acceptAndRelease(machine, controlOnly)).isTrue();
            assertThat(controlOnly.refCnt()).isZero();
            assertThat(allocator.allocations()).isZero();
            assertRawPhase(machine, new ControlState.ControlSuccess(ControlState.ControlType.DATA, 10), 6, false);
            assertThat(sinkCreations).hasValue(0);

            ByteBuf raw = buffer(10, 11, 12);
            assertThat(acceptAndRelease(machine, raw)).isTrue();
            assertThat(controlOnly.refCnt()).isZero();
            assertThat(raw.refCnt()).isZero();
            assertThat(allocator.allocations()).isZero();
            assertRawPhase(machine, new ControlState.ControlSuccess(ControlState.ControlType.DATA, 10), 3, true);
            assertThat(sinkCreations).hasValue(1);
            assertThat(rawSink.controls).containsExactly(new ControlState.ControlSuccess(ControlState.ControlType.DATA, 10));
            assertThat(rawSink.chunks).containsExactly(new byte[]{10, 11, 12});

            ByteBuf remaining = buffer(13, 14, 15);
            assertThat(acceptAndRelease(machine, remaining)).isTrue();
            assertThat(remaining.refCnt()).isZero();
            assertControlPhase(machine, ControlState.ControlEmpty.INSTANCE);
            assertThat(rawSink.chunks).containsExactly(new byte[]{10, 11, 12}, new byte[]{13, 14, 15});
        }
    }

    @Test
    void closeFailsAfterCompleteControlWhenPayloadNeverArrives() {
        AtomicInteger sinkCreations = new AtomicInteger();
        RecordingRawSink rawSink = new RecordingRawSink();
        ByteBuf controlOnly = ascii("000a");
        GitMinimalWireMachine machine = new GitMinimalWireMachine(
                UnpooledByteBufAllocator.DEFAULT,
                control -> {
                    sinkCreations.incrementAndGet();
                    rawSink.controls.add(control);
                    return rawSink;
                });
        try {
            assertThat(acceptAndRelease(machine, controlOnly)).isTrue();
            assertThat(controlOnly.refCnt()).isZero();
            assertThat(sinkCreations).hasValue(0);
            assertThatThrownBy(machine::close)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Incomplete Git pkt-line payload");
            assertThat(rawSink.controls).isEmpty();
        } finally {
            machine.close();
        }
    }

    @Test
    void forwardsRawRemainderFromSameInputAfterControl() {
        AtomicInteger sinkCreations = new AtomicInteger();
        RecordingRawSink rawSink = new RecordingRawSink();
        try (GitMinimalWireMachine machine = new GitMinimalWireMachine(
                UnpooledByteBufAllocator.DEFAULT,
                control -> {
                    sinkCreations.incrementAndGet();
                    rawSink.controls.add(control);
                    return rawSink;
                })) {
            ByteBuf input = asciiWithTail("000a", 10, 11);
            assertThat(acceptAndRelease(machine, input)).isTrue();

            assertThat(input.refCnt()).isZero();
            assertRawPhase(machine, new ControlState.ControlSuccess(ControlState.ControlType.DATA, 10), 4, true);
            assertThat(sinkCreations).hasValue(1);
            assertThat(rawSink.controls).containsExactly(new ControlState.ControlSuccess(ControlState.ControlType.DATA, 10));
            assertThat(rawSink.chunks).containsExactly(new byte[]{10, 11});

            ByteBuf remaining = buffer(12, 13, 14, 15);
            assertThat(acceptAndRelease(machine, remaining)).isTrue();
            assertThat(remaining.refCnt()).isZero();
            assertControlPhase(machine, ControlState.ControlEmpty.INSTANCE);
            assertThat(rawSink.chunks).containsExactly(new byte[]{10, 11}, new byte[]{12, 13, 14, 15});
        }
    }

    @Test
    void forwardsOnlyDeclaredPayloadBeforeReadingNextPktLineInSameInput() {
        AtomicInteger sinkCreations = new AtomicInteger();
        RecordingRawSink rawSink = new RecordingRawSink();
        try (GitMinimalWireMachine machine = new GitMinimalWireMachine(
                UnpooledByteBufAllocator.DEFAULT,
                control -> {
                    sinkCreations.incrementAndGet();
                    rawSink.controls.add(control);
                    return rawSink;
                })) {
            ByteBuf input = ascii("000aabcdef0007xyz");
            assertThat(acceptAndRelease(machine, input)).isTrue();

            assertThat(input.refCnt()).isZero();
            assertControlPhase(machine, ControlState.ControlEmpty.INSTANCE);
            assertThat(sinkCreations).hasValue(2);
            assertThat(rawSink.controls).containsExactly(
                    new ControlState.ControlSuccess(ControlState.ControlType.DATA, 10),
                    new ControlState.ControlSuccess(ControlState.ControlType.DATA, 7));
            assertThat(rawSink.chunks).containsExactly(asciiBytes("abcdef"), asciiBytes("xyz"));
        }
    }

    @Test
    void skipsSpecialPktLineBeforeReadingNextPktLineInSameInput() {
        AtomicInteger sinkCreations = new AtomicInteger();
        RecordingRawSink rawSink = new RecordingRawSink();
        try (GitMinimalWireMachine machine = new GitMinimalWireMachine(
                UnpooledByteBufAllocator.DEFAULT,
                control -> {
                    sinkCreations.incrementAndGet();
                    rawSink.controls.add(control);
                    return rawSink;
                })) {
            ByteBuf input = ascii("00000007xyz");
            assertThat(acceptAndRelease(machine, input)).isTrue();

            assertThat(input.refCnt()).isZero();
            assertControlPhase(machine, ControlState.ControlEmpty.INSTANCE);
            assertThat(sinkCreations).hasValue(1);
            assertThat(rawSink.controls).containsExactly(new ControlState.ControlSuccess(ControlState.ControlType.DATA, 7));
            assertThat(rawSink.chunks).containsExactly(asciiBytes("xyz"));
        }
    }

    @Test
    void forwardsRawRemainderWhenFragmentedControlCompletes() {
        CountingByteBufAllocator allocator = new CountingByteBufAllocator();
        AtomicInteger sinkCreations = new AtomicInteger();
        RecordingRawSink rawSink = new RecordingRawSink();
        try (GitMinimalWireMachine machine = new GitMinimalWireMachine(
                allocator,
                control -> {
                    sinkCreations.incrementAndGet();
                    rawSink.controls.add(control);
                    return rawSink;
                })) {
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
            assertThat(sinkCreations).hasValue(0);

            ByteBuf second = asciiWithTail("0a", 10, 11);
            assertThat(acceptAndRelease(machine, second)).isTrue();
            assertThat(second.refCnt()).isZero();
            assertThat(allocator.allocations()).isOne();
            assertRawPhase(machine, new ControlState.ControlSuccess(ControlState.ControlType.DATA, 10), 4, true);
            assertThat(sinkCreations).hasValue(1);
            assertThat(rawSink.controls).containsExactly(new ControlState.ControlSuccess(ControlState.ControlType.DATA, 10));
            assertThat(rawSink.chunks).containsExactly(new byte[]{10, 11});

            ByteBuf remaining = buffer(12, 13, 14, 15);
            assertThat(acceptAndRelease(machine, remaining)).isTrue();
            assertThat(remaining.refCnt()).isZero();
            assertControlPhase(machine, ControlState.ControlEmpty.INSTANCE);
            assertThat(rawSink.chunks).containsExactly(new byte[]{10, 11}, new byte[]{12, 13, 14, 15});
        }
    }

    @Test
    void reusesRawSinkForLaterRawInputs() {
        AtomicInteger sinkCreations = new AtomicInteger();
        RecordingRawSink rawSink = new RecordingRawSink();
        try (GitMinimalWireMachine machine = new GitMinimalWireMachine(
                UnpooledByteBufAllocator.DEFAULT,
                control -> {
                    sinkCreations.incrementAndGet();
                    rawSink.controls.add(control);
                    return rawSink;
                })) {
            ByteBuf first = asciiWithTail("000a", 10);
            assertThat(acceptAndRelease(machine, first)).isTrue();

            ByteBuf second = buffer(11, 12);
            assertThat(acceptAndRelease(machine, second)).isTrue();

            assertThat(first.refCnt()).isZero();
            assertThat(second.refCnt()).isZero();
            assertRawPhase(machine, new ControlState.ControlSuccess(ControlState.ControlType.DATA, 10), 3, true);
            assertThat(sinkCreations).hasValue(1);
            assertThat(rawSink.controls).containsExactly(new ControlState.ControlSuccess(ControlState.ControlType.DATA, 10));
            assertThat(rawSink.chunks).containsExactly(new byte[]{10}, new byte[]{11, 12});

            ByteBuf third = buffer(13, 14, 15);
            assertThat(acceptAndRelease(machine, third)).isTrue();
            assertThat(third.refCnt()).isZero();
            assertControlPhase(machine, ControlState.ControlEmpty.INSTANCE);
            assertThat(rawSink.chunks).containsExactly(new byte[]{10}, new byte[]{11, 12}, new byte[]{13, 14, 15});
        }
    }

    @Test
    void passesUploadPackWantPktLineInSingleInputChunk() {
        AtomicInteger sinkCreations = new AtomicInteger();
        RecordingRawSink rawSink = new RecordingRawSink();
        String wantPacket = "0032want 0123456789012345678901234567890123456789\n";
        try (GitMinimalWireMachine machine = new GitMinimalWireMachine(
                UnpooledByteBufAllocator.DEFAULT,
                control -> {
                    sinkCreations.incrementAndGet();
                    rawSink.controls.add(control);
                    return rawSink;
                })) {
            ByteBuf input = ascii(wantPacket);
            assertThat(acceptAndRelease(machine, input)).isTrue();

            assertThat(input.refCnt()).isZero();
            assertControlPhase(machine, ControlState.ControlEmpty.INSTANCE);
            assertThat(sinkCreations).hasValue(1);
            assertThat(rawSink.controls).containsExactly(new ControlState.ControlSuccess(ControlState.ControlType.DATA, 50));
            assertThat(rawSink.chunks).containsExactly(asciiBytes(wantPacket.substring(4)));
        }
    }

    @Test
    void passesUploadPackWantPktLineAcrossMultipleInputChunks() {
        AtomicInteger sinkCreations = new AtomicInteger();
        RecordingRawSink rawSink = new RecordingRawSink();
        String wantPacket = "0032want 0123456789012345678901234567890123456789\n";
        try (GitMinimalWireMachine machine = new GitMinimalWireMachine(
                UnpooledByteBufAllocator.DEFAULT,
                control -> {
                    sinkCreations.incrementAndGet();
                    rawSink.controls.add(control);
                    return rawSink;
                })) {
            ByteBuf first = ascii("00");
            assertThat(acceptAndRelease(machine, first)).isTrue();
            assertThat(first.refCnt()).isZero();
            assertThat(machine.state().phase()).isInstanceOfSatisfying(
                    GitMinimalWireMachine.ControlPhase.class,
                    phase -> assertThat(phase.state()).isInstanceOf(ControlState.MoreDataNeeded.class));
            assertThat(sinkCreations).hasValue(0);

            ByteBuf second = ascii("32");
            assertThat(acceptAndRelease(machine, second)).isTrue();
            assertThat(second.refCnt()).isZero();
            assertRawPhase(machine, new ControlState.ControlSuccess(ControlState.ControlType.DATA, 50), 46, false);
            assertThat(sinkCreations).hasValue(0);

            ByteBuf third = ascii(wantPacket.substring(4));
            assertThat(acceptAndRelease(machine, third)).isTrue();
            assertThat(third.refCnt()).isZero();
            assertControlPhase(machine, ControlState.ControlEmpty.INSTANCE);
            assertThat(sinkCreations).hasValue(1);
            assertThat(rawSink.controls).containsExactly(new ControlState.ControlSuccess(ControlState.ControlType.DATA, 50));
            assertThat(rawSink.chunks).containsExactly(asciiBytes(wantPacket.substring(4)));
        }
    }

    private static void assertControlPhase(
            GitMinimalWireMachine machine,
            ControlState expectedState) {
        assertThat(machine.state()).isEqualTo(new GitMinimalWireMachine.ComposedState(
                new GitMinimalWireMachine.ControlPhase(expectedState)));
    }

    private static void assertRawPhase(
            GitMinimalWireMachine machine,
            ControlState.ControlSuccess control,
            int remaining,
            boolean targetCreated) {
        assertThat(machine.state().phase()).isInstanceOfSatisfying(
                GitMinimalWireMachine.RawSinkPhase.class,
                phase -> {
                    assertThat(phase.control()).isEqualTo(control);
                    assertThat(phase.remaining()).isEqualTo(remaining);
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
