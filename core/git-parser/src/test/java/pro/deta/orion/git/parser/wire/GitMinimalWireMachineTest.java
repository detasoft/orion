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
    private static final int FRAME_SIZE = 10;
    private static final int STRUCTURAL_CAPACITY = 32 * 1024;

    @Test
    void createsRawSinkOnlyWhenRawBytesArriveAfterControl() {
        AtomicInteger sinkCreations = new AtomicInteger();
        RecordingRawSink rawSink = new RecordingRawSink();
        try (GitMinimalWireMachine machine = new GitMinimalWireMachine(
                UnpooledByteBufAllocator.DEFAULT,
                FRAME_SIZE,
                STRUCTURAL_CAPACITY,
                control -> {
                    sinkCreations.incrementAndGet();
                    return rawSink;
                })) {
            ByteBuf controlOnly = buffer(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
            machine.accept(controlOnly);
            assertThat(controlOnly.refCnt()).isZero();
            assertThat(machine.controlBytes()).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
            assertThat(sinkCreations).hasValue(0);

            ByteBuf raw = buffer(10, 11, 12);
            machine.accept(raw);
            assertThat(raw.refCnt()).isZero();
            assertThat(sinkCreations).hasValue(1);
            assertThat(rawSink.chunks).containsExactly(new byte[]{10, 11, 12});
        }
    }

    @Test
    void forwardsRawRemainderFromSameInputAfterControl() {
        AtomicInteger sinkCreations = new AtomicInteger();
        RecordingRawSink rawSink = new RecordingRawSink();
        try (GitMinimalWireMachine machine = new GitMinimalWireMachine(
                UnpooledByteBufAllocator.DEFAULT,
                FRAME_SIZE,
                STRUCTURAL_CAPACITY,
                control -> {
                    sinkCreations.incrementAndGet();
                    return rawSink;
                })) {
            ByteBuf input = buffer(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
            machine.accept(input);

            assertThat(input.refCnt()).isZero();
            assertThat(sinkCreations).hasValue(1);
            assertThat(machine.controlBytes()).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
            assertThat(rawSink.chunks).containsExactly(new byte[]{10, 11});
        }
    }

    @Test
    void forwardsRawRemainderWhenFragmentedControlCompletes() {
        AtomicInteger sinkCreations = new AtomicInteger();
        RecordingRawSink rawSink = new RecordingRawSink();
        try (GitMinimalWireMachine machine = new GitMinimalWireMachine(
                UnpooledByteBufAllocator.DEFAULT,
                FRAME_SIZE,
                STRUCTURAL_CAPACITY,
                control -> {
                    sinkCreations.incrementAndGet();
                    return rawSink;
                })) {
            ByteBuf first = buffer(0, 1, 2, 3, 4);
            machine.accept(first);
            assertThat(first.refCnt()).isZero();
            assertThat(sinkCreations).hasValue(0);

            ByteBuf second = buffer(5, 6, 7, 8, 9, 10, 11);
            machine.accept(second);
            assertThat(second.refCnt()).isZero();
            assertThat(sinkCreations).hasValue(1);
            assertThat(rawSink.chunks).containsExactly(new byte[]{10, 11});
        }
    }

    @Test
    void reusesRawSinkForLaterRawInputs() {
        AtomicInteger sinkCreations = new AtomicInteger();
        RecordingRawSink rawSink = new RecordingRawSink();
        try (GitMinimalWireMachine machine = new GitMinimalWireMachine(
                UnpooledByteBufAllocator.DEFAULT,
                FRAME_SIZE,
                STRUCTURAL_CAPACITY,
                control -> {
                    sinkCreations.incrementAndGet();
                    return rawSink;
                })) {
            ByteBuf first = buffer(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
            machine.accept(first);

            ByteBuf second = buffer(11, 12);
            machine.accept(second);

            assertThat(first.refCnt()).isZero();
            assertThat(second.refCnt()).isZero();
            assertThat(sinkCreations).hasValue(1);
            assertThat(rawSink.chunks).containsExactly(new byte[]{10}, new byte[]{11, 12});
        }
    }

    private static ByteBuf buffer(int... values) {
        ByteBuf buffer = Unpooled.buffer(values.length);
        for (int value : values) {
            buffer.writeByte(value);
        }
        return buffer;
    }

    private static final class RecordingRawSink implements GitMinimalWireMachine.RawSink {
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
