package pro.deta.orion.agentd.transport;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CborSequenceDecoderTest {
    @Test void splitsFragmentedAndCoalescedItemsWithoutChangingBytes() {
        CborSequenceDecoder decoder = new CborSequenceDecoder(32);
        byte[] sequence = {(byte) 0x82, 1, 2, (byte) 0x43, 9, 8, 7};
        assertThat(decoder.accept(ByteBuffer.wrap(sequence, 0, 2))).isEmpty();
        assertThat(decoder.accept(ByteBuffer.wrap(sequence, 2, 5)))
                .containsExactly(new byte[]{(byte) 0x82, 1, 2}, new byte[]{(byte) 0x43, 9, 8, 7});
    }
    @Test void keepsTruncatedItemUntilMoreDataArrives() {
        CborSequenceDecoder decoder = new CborSequenceDecoder(32);
        assertThat(decoder.accept(ByteBuffer.wrap(new byte[]{0x43, 1}))).isEmpty();
        assertThat(decoder.accept(ByteBuffer.wrap(new byte[]{2, 3}))).containsExactly(new byte[]{0x43, 1, 2, 3});
    }
    @Test void rejectsBoundedAccumulation() {
        CborSequenceDecoder decoder = new CborSequenceDecoder(3);
        assertThatThrownBy(() -> decoder.accept(ByteBuffer.wrap(new byte[]{0x44, 1, 2, 3})))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void acceptsCoalescedItemsWhoseCombinedBytesExceedThePerItemBound() {
        CborSequenceDecoder decoder = new CborSequenceDecoder(4);
        assertThat(decoder.accept(ByteBuffer.wrap(new byte[]{0x43, 1, 2, 3, 0x43, 4, 5, 6})))
                .containsExactly(new byte[]{0x43, 1, 2, 3}, new byte[]{0x43, 4, 5, 6});
    }
    @Test void rejectsTruncationAndReservedAdditionalInformation() {
        CborSequenceDecoder decoder = new CborSequenceDecoder(32);
        decoder.accept(ByteBuffer.wrap(new byte[]{0x43, 1}));
        assertThatThrownBy(decoder::finish).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CborSequenceDecoder(32)
                .accept(ByteBuffer.wrap(new byte[]{0x1c}))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void preservesFragmentedNestedIndefiniteValues() {
        byte[] item = {
                (byte) 0x9f,
                (byte) 0xbf, 1, (byte) 0x7f, 0x62, 'h', 'i', 0x61, '!', (byte) 0xff, (byte) 0xff,
                (byte) 0x5f, 0x42, 1, 2, 0x41, 3, (byte) 0xff,
                (byte) 0xff
        };
        CborSequenceDecoder decoder = new CborSequenceDecoder(64);

        assertThat(decoder.accept(ByteBuffer.wrap(item, 0, 5))).isEmpty();
        assertThat(decoder.accept(ByteBuffer.wrap(item, 5, 8))).isEmpty();
        assertThat(decoder.accept(ByteBuffer.wrap(item, 13, item.length - 13))).containsExactly(item);
    }

    @Test
    void preservesEveryIndefiniteContainerAndStringForm() {
        byte[] sequence = {
                (byte) 0x9f, 1, (byte) 0xff,
                (byte) 0xbf, 1, 2, (byte) 0xff,
                (byte) 0x5f, 0x41, 3, (byte) 0xff,
                (byte) 0x7f, 0x61, 'x', (byte) 0xff
        };
        CborSequenceDecoder decoder = new CborSequenceDecoder(8);

        assertThat(decoder.accept(ByteBuffer.wrap(sequence, 0, 2))).isEmpty();
        assertThat(decoder.accept(ByteBuffer.wrap(sequence, 2, sequence.length - 2)))
                .containsExactly(
                        new byte[]{(byte) 0x9f, 1, (byte) 0xff},
                        new byte[]{(byte) 0xbf, 1, 2, (byte) 0xff},
                        new byte[]{(byte) 0x5f, 0x41, 3, (byte) 0xff},
                        new byte[]{(byte) 0x7f, 0x61, 'x', (byte) 0xff});
    }

    @Test
    void rejectsInvalidBreaksMapsAndIndefiniteStringChunks() {
        assertRejected((byte) 0xff);
        assertRejected((byte) 0x81, (byte) 0xff);
        assertRejected((byte) 0xbf, (byte) 1, (byte) 0xff);
        assertRejected((byte) 0x5f, (byte) 0x61, (byte) 'x', (byte) 0xff);
        assertRejected((byte) 0x7f, (byte) 0x7f, (byte) 0xff, (byte) 0xff);
        assertRejected((byte) 0x7f, (byte) 0x41, (byte) 'x', (byte) 0xff);
    }

    @Test
    void enforcesBoundsForDefiniteAndIndefiniteItems() {
        assertThat(new CborSequenceDecoder(3).accept(
                ByteBuffer.wrap(new byte[]{(byte) 0x9f, 1, (byte) 0xff})))
                .containsExactly(new byte[]{(byte) 0x9f, 1, (byte) 0xff});
        assertThatThrownBy(() -> new CborSequenceDecoder(4)
                .accept(ByteBuffer.wrap(new byte[]{0x44})))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CborSequenceDecoder(4)
                .accept(ByteBuffer.wrap(new byte[]{(byte) 0x84})))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CborSequenceDecoder(1)
                .accept(ByteBuffer.wrap(new byte[]{(byte) 0x9f})))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDeepItems() {
        byte[] deep = new byte[66];
        java.util.Arrays.fill(deep, (byte) 0x81);
        deep[65] = 0;
        assertThatThrownBy(() -> new CborSequenceDecoder(128).accept(ByteBuffer.wrap(deep)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void respectsConfiguredNestingDepth() {
        byte[] nested = {(byte) 0x81, (byte) 0x81, 0};

        assertThat(new CborSequenceDecoder(3, 2).accept(ByteBuffer.wrap(nested))).containsExactly(nested);
        assertThatThrownBy(() -> new CborSequenceDecoder(3, 1).accept(ByteBuffer.wrap(nested)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertRejected(byte... bytes) {
        assertThatThrownBy(() -> new CborSequenceDecoder(32).accept(ByteBuffer.wrap(bytes)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
