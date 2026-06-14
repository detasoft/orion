package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GitFixedControlFrameReaderTest {
    private static final int FRAME_SIZE = 10;

    @Test
    void readsFragmentedControlAcrossInputs() {
        GitFixedControlFrameReader reader = new GitFixedControlFrameReader(FRAME_SIZE);

        ByteBuf first = buffer(0, 1, 2, 3, 4);
        try {
            assertThat(reader.accept(first))
                    .isEqualTo(GitFixedControlFrameReader.ControlReadState.NEEDS_MORE_CONTROL);
            assertThat(first.readerIndex()).isEqualTo(5);
            assertThat(first.readableBytes()).isZero();
        } finally {
            first.release();
        }

        ByteBuf second = buffer(5, 6, 7, 8, 9);
        try {
            assertThat(reader.accept(second))
                    .isEqualTo(GitFixedControlFrameReader.ControlReadState.CONTROL_COMPLETE);
            assertThat(second.readerIndex()).isEqualTo(5);
            assertThat(second.readableBytes()).isZero();
        } finally {
            second.release();
        }
    }

    @Test
    void readsCompleteControlFromInboundBuffer() {
        GitFixedControlFrameReader reader = new GitFixedControlFrameReader(FRAME_SIZE);

        ByteBuf input = buffer(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
        try {
            assertThat(reader.accept(input))
                    .isEqualTo(GitFixedControlFrameReader.ControlReadState.CONTROL_COMPLETE);
            assertThat(input.readerIndex()).isEqualTo(FRAME_SIZE);
            assertThat(input.readableBytes()).isZero();
        } finally {
            input.release();
        }
    }

    @Test
    void leavesUnreadTailForCallerWhenFragmentedControlCompletes() {
        GitFixedControlFrameReader reader = new GitFixedControlFrameReader(FRAME_SIZE);

        ByteBuf first = buffer(0, 1, 2, 3, 4);
        try {
            assertThat(reader.accept(first))
                    .isEqualTo(GitFixedControlFrameReader.ControlReadState.NEEDS_MORE_CONTROL);
        } finally {
            first.release();
        }

        ByteBuf second = buffer(5, 6, 7, 8, 9, 10, 11);
        try {
            assertThat(reader.accept(second))
                    .isEqualTo(GitFixedControlFrameReader.ControlReadState.CONTROL_COMPLETE);
            assertThat(second.readerIndex()).isEqualTo(5);
            assertThat(second.readableBytes()).isEqualTo(2);
        } finally {
            second.release();
        }
    }

    @Test
    void returnsAlreadyCompleteWithoutConsumingInput() {
        GitFixedControlFrameReader reader = new GitFixedControlFrameReader(FRAME_SIZE);

        ByteBuf first = buffer(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
        try {
            assertThat(reader.accept(first))
                    .isEqualTo(GitFixedControlFrameReader.ControlReadState.CONTROL_COMPLETE);
        } finally {
            first.release();
        }

        ByteBuf second = buffer(10);
        try {
            int readableBefore = second.readableBytes();
            assertThat(reader.accept(second))
                    .isEqualTo(GitFixedControlFrameReader.ControlReadState.ALREADY_COMPLETE);
            assertThat(second.readableBytes()).isEqualTo(readableBefore);
        } finally {
            second.release();
        }
    }

    private static ByteBuf buffer(int... values) {
        ByteBuf buffer = Unpooled.buffer(values.length);
        for (int value : values) {
            buffer.writeByte(value);
        }
        return buffer;
    }
}
