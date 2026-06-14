package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GitMinimalWireMachineTest {
    @Test
    void createsRawSinkOnlyWhenRawBytesArriveAfterControl() {
        CountingByteBufAllocator allocator = new CountingByteBufAllocator();
        AtomicInteger sinkCreations = new AtomicInteger();
        RecordingRawSink rawSink = new RecordingRawSink();
        try (GitMinimalWireMachine machine = new GitMinimalWireMachine(
                allocator,
                control -> {
                    sinkCreations.incrementAndGet();
                    rawSink.controls.add(readableBytes(control));
                    return rawSink;
                })) {
            assertThat(allocator.allocations()).isZero();

            ByteBuf controlOnly = ascii("000aabcdef");
            assertThat(acceptAndRelease(machine, controlOnly)).isTrue();
            assertThat(controlOnly.refCnt()).isOne();
            assertThat(allocator.allocations()).isZero();
            assertThat(machine.state()).isEqualTo(state(
                    GitMinimalWireMachine.Phase.RAW,
                    false,
                    0));
            assertThat(sinkCreations).hasValue(0);

            ByteBuf raw = buffer(10, 11, 12);
            assertThat(acceptAndRelease(machine, raw)).isTrue();
            assertThat(controlOnly.refCnt()).isZero();
            assertThat(raw.refCnt()).isZero();
            assertThat(allocator.allocations()).isZero();
            assertThat(machine.state()).isEqualTo(state(
                    GitMinimalWireMachine.Phase.RAW,
                    true,
                    0));
            assertThat(sinkCreations).hasValue(1);
            assertThat(rawSink.controls).containsExactly(asciiBytes("000aabcdef"));
            assertThat(rawSink.chunks).containsExactly(new byte[]{10, 11, 12});
        }
    }

    @Test
    void releasesHeldCompleteControlWhenClosedBeforeRawArrives() {
        AtomicInteger sinkCreations = new AtomicInteger();
        RecordingRawSink rawSink = new RecordingRawSink();
        ByteBuf controlOnly = ascii("000aabcdef");
        GitMinimalWireMachine machine = new GitMinimalWireMachine(
                UnpooledByteBufAllocator.DEFAULT,
                control -> {
                    sinkCreations.incrementAndGet();
                    rawSink.controls.add(readableBytes(control));
                    return rawSink;
                });
        try {
            assertThat(acceptAndRelease(machine, controlOnly)).isTrue();
            assertThat(controlOnly.refCnt()).isOne();
            assertThat(sinkCreations).hasValue(0);
        } finally {
            machine.close();
        }
        assertThat(controlOnly.refCnt()).isZero();
        assertThat(rawSink.controls).isEmpty();
    }

    @Test
    void forwardsRawRemainderFromSameInputAfterControl() {
        AtomicInteger sinkCreations = new AtomicInteger();
        RecordingRawSink rawSink = new RecordingRawSink();
        try (GitMinimalWireMachine machine = new GitMinimalWireMachine(
                UnpooledByteBufAllocator.DEFAULT,
                control -> {
                    sinkCreations.incrementAndGet();
                    rawSink.controls.add(readableBytes(control));
                    return rawSink;
                })) {
            ByteBuf input = asciiWithTail("000aabcdef", 10, 11);
            assertThat(acceptAndRelease(machine, input)).isTrue();

            assertThat(input.refCnt()).isZero();
            assertThat(machine.state()).isEqualTo(state(
                    GitMinimalWireMachine.Phase.RAW,
                    true,
                    0));
            assertThat(sinkCreations).hasValue(1);
            assertThat(rawSink.controls).containsExactly(asciiBytes("000aabcdef"));
            assertThat(rawSink.chunks).containsExactly(new byte[]{10, 11});
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
                    rawSink.controls.add(readableBytes(control));
                    return rawSink;
                })) {
            assertThat(allocator.allocations()).isZero();

            ByteBuf first = ascii("000aab");
            assertThat(acceptAndRelease(machine, first)).isTrue();
            assertThat(first.refCnt()).isZero();
            assertThat(allocator.allocations()).isOne();
            assertThat(allocator.lastInitialCapacity()).isEqualTo(10);
            assertThat(allocator.lastMaxCapacity()).isEqualTo(GitFixedControlFrameReader.MAX_PKT_LINE_LENGTH);
            assertThat(machine.state()).isEqualTo(state(
                    GitMinimalWireMachine.Phase.CONTROL,
                    false,
                    6));
            assertThat(sinkCreations).hasValue(0);

            ByteBuf second = asciiWithTail("cdef", 10, 11);
            assertThat(acceptAndRelease(machine, second)).isTrue();
            assertThat(second.refCnt()).isZero();
            assertThat(allocator.allocations()).isOne();
            assertThat(machine.state()).isEqualTo(state(
                    GitMinimalWireMachine.Phase.RAW,
                    true,
                    0));
            assertThat(sinkCreations).hasValue(1);
            assertThat(rawSink.controls).containsExactly(asciiBytes("000aabcdef"));
            assertThat(rawSink.chunks).containsExactly(new byte[]{10, 11});
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
                    rawSink.controls.add(readableBytes(control));
                    return rawSink;
                })) {
            ByteBuf first = asciiWithTail("000aabcdef", 10);
            assertThat(acceptAndRelease(machine, first)).isTrue();

            ByteBuf second = buffer(11, 12);
            assertThat(acceptAndRelease(machine, second)).isTrue();

            assertThat(first.refCnt()).isZero();
            assertThat(second.refCnt()).isZero();
            assertThat(machine.state()).isEqualTo(state(
                    GitMinimalWireMachine.Phase.RAW,
                    true,
                    0));
            assertThat(sinkCreations).hasValue(1);
            assertThat(rawSink.controls).containsExactly(asciiBytes("000aabcdef"));
            assertThat(rawSink.chunks).containsExactly(new byte[]{10}, new byte[]{11, 12});
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
                    rawSink.controls.add(readableBytes(control));
                    return rawSink;
                })) {
            ByteBuf input = asciiWithTail(wantPacket, 'P', 'A', 'C', 'K');
            assertThat(acceptAndRelease(machine, input)).isTrue();

            assertThat(input.refCnt()).isZero();
            assertThat(machine.state()).isEqualTo(state(
                    GitMinimalWireMachine.Phase.RAW,
                    true,
                    0));
            assertThat(sinkCreations).hasValue(1);
            assertThat(rawSink.controls).containsExactly(asciiBytes(wantPacket));
            assertThat(rawSink.chunks).containsExactly(asciiBytes("PACK"));
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
                    rawSink.controls.add(readableBytes(control));
                    return rawSink;
                })) {
            ByteBuf first = ascii("0032want 0123456789");
            assertThat(acceptAndRelease(machine, first)).isTrue();
            assertThat(first.refCnt()).isZero();
            assertThat(machine.state()).isEqualTo(state(
                    GitMinimalWireMachine.Phase.CONTROL,
                    false,
                    19));
            assertThat(sinkCreations).hasValue(0);

            ByteBuf second = ascii("01234567890123456789");
            assertThat(acceptAndRelease(machine, second)).isTrue();
            assertThat(second.refCnt()).isZero();
            assertThat(machine.state()).isEqualTo(state(
                    GitMinimalWireMachine.Phase.CONTROL,
                    false,
                    39));
            assertThat(sinkCreations).hasValue(0);

            ByteBuf third = asciiWithTail("0123456789\n", 'P', 'A', 'C', 'K');
            assertThat(acceptAndRelease(machine, third)).isTrue();
            assertThat(third.refCnt()).isZero();
            assertThat(machine.state()).isEqualTo(state(
                    GitMinimalWireMachine.Phase.RAW,
                    true,
                    0));
            assertThat(sinkCreations).hasValue(1);
            assertThat(rawSink.controls).containsExactly(asciiBytes(wantPacket));
            assertThat(rawSink.chunks).containsExactly(asciiBytes("PACK"));
        }
    }

    private static GitMinimalWireMachine.ComposedState state(
            GitMinimalWireMachine.Phase phase,
            boolean rawSinkCreated,
            int bufferedControlBytes) {
        return new GitMinimalWireMachine.ComposedState(
                phase,
                rawSinkCreated,
                bufferedControlBytes);
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

    private static byte[] readableBytes(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    private static byte[] asciiBytes(String value) {
        byte[] bytes = new byte[value.length()];
        for (int i = 0; i < value.length(); i++) {
            bytes[i] = (byte) value.charAt(i);
        }
        return bytes;
    }

    private static final class RecordingRawSink implements GitMinimalWireMachine.RawSink {
        private final List<byte[]> controls = new ArrayList<>();
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
