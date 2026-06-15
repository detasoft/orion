package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitNativeUtilsTest {
    @Test
    void readsPktLineLengthFromLowercaseHexHeader() {
        ByteBuf input = ascii("0032");
        try {
            assertThat(GitNativeUtils.packetLength(input, 0)).isEqualTo(50);
        } finally {
            input.release();
        }
    }

    @Test
    void readsPktLineLengthFromUppercaseHexHeaderAtOffsetWithoutMovingReaderIndex() {
        ByteBuf input = ascii("xxFFF0yy");
        try {
            input.readerIndex(1);

            assertThat(GitNativeUtils.packetLength(input, 2)).isEqualTo(65_520);
            assertThat(input.readerIndex()).isOne();
            assertThat(input.readableBytes()).isEqualTo(7);
        } finally {
            input.release();
        }
    }

    @Test
    void rejectsNonHexByteInPktLineHeader() {
        ByteBuf input = ascii("00g1");
        try {
            assertThatThrownBy(() -> GitNativeUtils.packetLength(input, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Pkt-line length contains non-hex byte");
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
