package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.wire.control.ControlState;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitFixedControlFrameReaderTest {
    @Test
    void readsCompleteHeaderWithoutAllocatingFragmentState() {
        CountingByteBufAllocator allocator = new CountingByteBufAllocator();
        GitFixedControlFrameReader reader = new GitFixedControlFrameReader(allocator);
        ByteBuf input = ascii("000aabcdef");
        try {
            ControlState state = reader.accept(ControlState.ControlEmpty.INSTANCE, input);
            assertThat(state)
                    .isEqualTo(new ControlState.ControlSuccess(ControlState.ControlType.DATA, 10));

            assertThat(input.readerIndex()).isEqualTo(4);
            assertThat(input.readableBytes()).isEqualTo(6);
            assertThat(input.refCnt()).isOne();
            assertThat(allocator.allocations()).isZero();
        } finally {
            input.release();
        }
    }

    @Test
    void copiesFirstHeaderFragmentUntilSecondFragmentCompletesHeader() {
        CountingByteBufAllocator allocator = new CountingByteBufAllocator();
        GitFixedControlFrameReader reader = new GitFixedControlFrameReader(allocator);

        ByteBuf first = ascii("00");
        ControlState firstState = reader.accept(ControlState.ControlEmpty.INSTANCE, first);
        assertThat(firstState).isInstanceOf(ControlState.MoreDataNeeded.class);
        ByteBuf firstFragment = ((ControlState.MoreDataNeeded) firstState).fragment();
        assertThat(first.readerIndex()).isEqualTo(2);
        assertThat(first.readableBytes()).isZero();
        assertThat(first.refCnt()).isOne();
        assertThat(firstFragment.readableBytes()).isEqualTo(2);
        assertThat(allocator.allocations()).isOne();
        assertThat(allocator.lastInitialCapacity()).isEqualTo(2);
        assertThat(allocator.lastMaxCapacity()).isEqualTo(4);

        first.release();
        assertThat(first.refCnt()).isZero();
        assertThat(firstFragment.refCnt()).isOne();

        ByteBuf second = ascii("0aPACK");
        try {
            assertThat(reader.accept(firstState, second))
                    .isEqualTo(new ControlState.ControlSuccess(ControlState.ControlType.DATA, 10));

            assertThat(second.readerIndex()).isEqualTo(2);
            assertThat(second.readableBytes()).isEqualTo(4);
            assertThat(second.refCnt()).isOne();
            assertThat(firstFragment.refCnt()).isZero();
            assertThat(allocator.allocations()).isOne();
        } finally {
            second.release();
        }
    }

    @Test
    void releasesAllRetainedFragmentsWhenHeaderCompletesAfterThreeInputs() {
        CountingByteBufAllocator allocator = new CountingByteBufAllocator();
        GitFixedControlFrameReader reader = new GitFixedControlFrameReader(allocator);

        ByteBuf first = ascii("0");
        ControlState firstState = reader.accept(ControlState.ControlEmpty.INSTANCE, first);
        ByteBuf firstFragment = ((ControlState.MoreDataNeeded) firstState).fragment();
        first.release();
        assertThat(first.refCnt()).isZero();
        assertThat(firstFragment.refCnt()).isOne();
        assertThat(allocator.allocations()).isOne();

        ByteBuf second = ascii("0");
        ControlState secondState = reader.accept(firstState, second);
        ByteBuf secondFragment = ((ControlState.MoreDataNeeded) secondState).fragment();
        second.release();
        assertThat(first.refCnt()).isZero();
        assertThat(second.refCnt()).isZero();
        assertThat(secondFragment).isSameAs(firstFragment);
        assertThat(firstFragment.refCnt()).isOne();
        assertThat(secondFragment.readableBytes()).isEqualTo(2);
        assertThat(allocator.allocations()).isOne();

        ByteBuf third = ascii("0aPACK");
        try {
            assertThat(reader.accept(secondState, third))
                    .isEqualTo(new ControlState.ControlSuccess(ControlState.ControlType.DATA, 10));

            assertThat(third.readerIndex()).isEqualTo(2);
            assertThat(third.readableBytes()).isEqualTo(4);
            assertThat(firstFragment.refCnt()).isZero();
            assertThat(secondFragment.refCnt()).isZero();
            assertThat(allocator.allocations()).isOne();
        } finally {
            third.release();
        }
    }

    @Test
    void returnsMoreDataNeededForEachSingleHeaderByteUntilFourthByteCompletesHeader() {
        CountingByteBufAllocator allocator = new CountingByteBufAllocator();
        GitFixedControlFrameReader reader = new GitFixedControlFrameReader(allocator);

        ByteBuf first = ascii("0");
        ControlState firstState = reader.accept(ControlState.ControlEmpty.INSTANCE, first);
        assertThat(firstState).isInstanceOf(ControlState.MoreDataNeeded.class);
        ByteBuf firstFragment = ((ControlState.MoreDataNeeded) firstState).fragment();
        first.release();
        assertThat(first.refCnt()).isZero();
        assertThat(firstFragment.refCnt()).isOne();

        ByteBuf second = ascii("0");
        ControlState secondState = reader.accept(firstState, second);
        assertThat(secondState).isInstanceOf(ControlState.MoreDataNeeded.class);
        ByteBuf secondFragment = ((ControlState.MoreDataNeeded) secondState).fragment();
        second.release();
        assertThat(second.refCnt()).isZero();
        assertThat(secondFragment).isSameAs(firstFragment);
        assertThat(secondFragment.refCnt()).isOne();
        assertThat(allocator.allocations()).isOne();

        ByteBuf third = ascii("0");
        ControlState thirdState = reader.accept(secondState, third);
        assertThat(thirdState).isInstanceOf(ControlState.MoreDataNeeded.class);
        ByteBuf thirdFragment = ((ControlState.MoreDataNeeded) thirdState).fragment();
        third.release();
        assertThat(third.refCnt()).isZero();
        assertThat(thirdFragment).isSameAs(firstFragment);
        assertThat(thirdFragment.refCnt()).isOne();
        assertThat(allocator.allocations()).isOne();

        ByteBuf fourth = ascii("aPACK");
        try {
            assertThat(reader.accept(thirdState, fourth))
                    .isEqualTo(new ControlState.ControlSuccess(ControlState.ControlType.DATA, 10));

            assertThat(fourth.readerIndex()).isEqualTo(1);
            assertThat(fourth.readableBytes()).isEqualTo(4);
            assertThat(firstFragment.refCnt()).isZero();
            assertThat(secondFragment.refCnt()).isZero();
            assertThat(thirdFragment.refCnt()).isZero();
            assertThat(allocator.allocations()).isOne();
        } finally {
            fourth.release();
        }
    }

    @Test
    void releasesAllSingleByteHeaderFragmentsWhenFourthByteCompletesInvalidHeader() {
        CountingByteBufAllocator allocator = new CountingByteBufAllocator();
        GitFixedControlFrameReader reader = new GitFixedControlFrameReader(allocator);

        ByteBuf first = ascii("z");
        ControlState firstState = reader.accept(ControlState.ControlEmpty.INSTANCE, first);
        assertThat(firstState).isInstanceOf(ControlState.MoreDataNeeded.class);
        ByteBuf firstFragment = ((ControlState.MoreDataNeeded) firstState).fragment();
        first.release();
        assertThat(first.refCnt()).isZero();
        assertThat(firstFragment.refCnt()).isOne();

        ByteBuf second = ascii("z");
        ControlState secondState = reader.accept(firstState, second);
        assertThat(secondState).isInstanceOf(ControlState.MoreDataNeeded.class);
        ByteBuf secondFragment = ((ControlState.MoreDataNeeded) secondState).fragment();
        second.release();
        assertThat(second.refCnt()).isZero();
        assertThat(secondFragment).isSameAs(firstFragment);
        assertThat(secondFragment.refCnt()).isOne();
        assertThat(allocator.allocations()).isOne();

        ByteBuf third = ascii("z");
        ControlState thirdState = reader.accept(secondState, third);
        assertThat(thirdState).isInstanceOf(ControlState.MoreDataNeeded.class);
        ByteBuf thirdFragment = ((ControlState.MoreDataNeeded) thirdState).fragment();
        third.release();
        assertThat(third.refCnt()).isZero();
        assertThat(thirdFragment).isSameAs(firstFragment);
        assertThat(thirdFragment.refCnt()).isOne();
        assertThat(allocator.allocations()).isOne();

        ByteBuf fourth = ascii("zPACK");
        try {
            assertThatThrownBy(() -> reader.accept(thirdState, fourth))
                    .isInstanceOfSatisfying(GitWireException.class, error -> assertThat(error.error())
                            .isEqualTo(new GitWireError(
                                    GitWireError.Kind.INVALID_HEX_HEADER,
                                    GitWireError.Phase.CONTROL_HEADER,
                                    GitWireError.UNKNOWN_INDEX,
                                    0,
                                    "Pkt-line length contains non-hex byte")));

            assertThat(fourth.readerIndex()).isEqualTo(1);
            assertThat(fourth.readableBytes()).isEqualTo(4);
            assertThat(firstFragment.refCnt()).isZero();
            assertThat(secondFragment.refCnt()).isZero();
            assertThat(thirdFragment.refCnt()).isZero();
            assertThat(fourth.refCnt()).isEqualTo(1);
            assertThat(allocator.allocations()).isOne();
        } finally {
            fourth.release();
        }
    }

    @Test
    void rejectsAcceptAfterCompleteWithoutConsumingInput() {
        CountingByteBufAllocator allocator = new CountingByteBufAllocator();
        GitFixedControlFrameReader reader = new GitFixedControlFrameReader(allocator);
        ByteBuf first = ascii("000aabcdef");
        try {
            ControlState state = reader.accept(ControlState.ControlEmpty.INSTANCE, first);
            assertThat(state).isEqualTo(new ControlState.ControlSuccess(ControlState.ControlType.DATA, 10));
        } finally {
            first.release();
        }

        ByteBuf second = ascii("x");
        try {
            int readableBefore = second.readableBytes();
            assertThatThrownBy(() -> reader.accept(new ControlState.ControlSuccess(ControlState.ControlType.DATA, 10), second))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already complete");
            assertThat(second.readableBytes()).isEqualTo(readableBefore);
        } finally {
            second.release();
        }
    }

    @Test
    void readsSpecialPktLinePacketsWithoutAllocatingStructuralBuffer() {
        assertSpecialPacket("0000", ControlState.ControlType.FLUSH);
        assertSpecialPacket("0001", ControlState.ControlType.DELIMITER);
        assertSpecialPacket("0002", ControlState.ControlType.RESPONSE_END);
    }

    @Test
    void rejectsInvalidPktLineHeader() {
        CountingByteBufAllocator allocator = new CountingByteBufAllocator();
        GitFixedControlFrameReader reader = new GitFixedControlFrameReader(allocator);
        ByteBuf input = ascii("zzzz");
        try {
            assertThatThrownBy(() -> reader.accept(ControlState.ControlEmpty.INSTANCE, input))
                    .isInstanceOfSatisfying(GitWireException.class, error -> assertThat(error.error())
                            .isEqualTo(new GitWireError(
                                    GitWireError.Kind.INVALID_HEX_HEADER,
                                    GitWireError.Phase.CONTROL_HEADER,
                                    GitWireError.UNKNOWN_INDEX,
                                    0,
                                    "Pkt-line length contains non-hex byte")));
        } finally {
            input.release();
        }
    }

    @Test
    void resetsAfterCompletedFragmentedHeaderIsInvalid() {
        CountingByteBufAllocator allocator = new CountingByteBufAllocator();
        GitFixedControlFrameReader reader = new GitFixedControlFrameReader(allocator);

        ByteBuf first = ascii("zz");
        ControlState firstState = reader.accept(ControlState.ControlEmpty.INSTANCE, first);
        ByteBuf firstFragment = ((ControlState.MoreDataNeeded) firstState).fragment();
        first.release();
        assertThat(first.refCnt()).isZero();
        assertThat(firstFragment.refCnt()).isOne();

        ByteBuf second = ascii("zzPACK");
        try {
            assertThatThrownBy(() -> reader.accept(firstState, second))
                    .isInstanceOfSatisfying(GitWireException.class, error -> assertThat(error.error().kind())
                            .isEqualTo(GitWireError.Kind.INVALID_HEX_HEADER));

            assertThat(firstFragment.refCnt()).isZero();
        } finally {
            second.release();
        }

        ByteBuf third = ascii("000aPACK");
        try {
            assertThat(reader.accept(ControlState.ControlEmpty.INSTANCE, third))
                    .isEqualTo(new ControlState.ControlSuccess(ControlState.ControlType.DATA, 10));
            assertThat(third.readerIndex()).isEqualTo(4);
            assertThat(third.readableBytes()).isEqualTo(4);
        } finally {
            third.release();
        }
    }

    @Test
    void rejectsReservedPktLineLength() {
        CountingByteBufAllocator allocator = new CountingByteBufAllocator();
        GitFixedControlFrameReader reader = new GitFixedControlFrameReader(allocator);
        ByteBuf input = ascii("0003");
        try {
            assertThatThrownBy(() -> reader.accept(ControlState.ControlEmpty.INSTANCE, input))
                    .isInstanceOfSatisfying(GitWireException.class, error -> assertThat(error.error())
                            .isEqualTo(new GitWireError(
                                    GitWireError.Kind.RESERVED_LENGTH,
                                    GitWireError.Phase.CONTROL_HEADER,
                                    GitWireError.UNKNOWN_INDEX,
                                    0,
                                    "Pkt-line length 0003 is reserved")));
        } finally {
            input.release();
        }
    }

    @Test
    void rejectsPktLinePacketAboveGitLimit() {
        CountingByteBufAllocator allocator = new CountingByteBufAllocator();
        GitFixedControlFrameReader reader = new GitFixedControlFrameReader(allocator);
        ByteBuf input = ascii("fff1");
        try {
            assertThatThrownBy(() -> reader.accept(ControlState.ControlEmpty.INSTANCE, input))
                    .isInstanceOfSatisfying(GitWireException.class, error -> assertThat(error.error())
                            .isEqualTo(new GitWireError(
                                    GitWireError.Kind.LENGTH_EXCEEDS_LIMIT,
                                    GitWireError.Phase.CONTROL_HEADER,
                                    GitWireError.UNKNOWN_INDEX,
                                    0,
                                    "Pkt-line length exceeds Git pkt-line limit")));
        } finally {
            input.release();
        }
    }

    private static void assertSpecialPacket(String header, ControlState.ControlType type) {
        CountingByteBufAllocator allocator = new CountingByteBufAllocator();
        GitFixedControlFrameReader reader = new GitFixedControlFrameReader(allocator);
        ByteBuf input = ascii(header + "tail");
        try {
            assertThat(reader.accept(ControlState.ControlEmpty.INSTANCE, input))
                    .isEqualTo(new ControlState.ControlSuccess(type, 4));

            assertThat(input.readerIndex()).isEqualTo(4);
            assertThat(input.readableBytes()).isEqualTo(4);
            assertThat(allocator.allocations()).isZero();
        } finally {
            input.release();
        }
    }

    private static ByteBuf ascii(String value) {
        ByteBuf buffer = Unpooled.buffer(value.length());
        for (int i = 0; i < value.length(); i++) {
            buffer.writeByte(value.charAt(i));
        }
        return buffer;
    }
}
