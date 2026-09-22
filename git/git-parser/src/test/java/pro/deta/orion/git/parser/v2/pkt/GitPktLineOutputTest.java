package pro.deta.orion.git.parser.v2.pkt;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitPktLineOutputTest {
    @Test
    void splitsAtTheMaximumPacketLengthWithoutChangingTheBorrowedBuffer() throws Exception {
        int firstPayloadLength = GitPktLine.MAX_PKT_LINE_LENGTH - 5;
        byte[] payload = new byte[firstPayloadLength + 3];
        Arrays.fill(payload, 0, firstPayloadLength, (byte) 'a');
        System.arraycopy(new byte[]{'b', 'c', 'd'}, 0, payload, firstPayloadLength, 3);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ByteBuf source = Unpooled.wrappedBuffer(payload);
        try {
            GitPktLineOutput output = new GitPktLineOutput(new OutputStreamBufferedByteOutput(bytes),
                    SideBand.DATA, GitPktLine.MAX_PKT_LINE_LENGTH);
            output.write(source);
            assertThat(source.readerIndex()).isZero();
            assertThat(source.writerIndex()).isEqualTo(payload.length);
            assertThat(source.refCnt()).isEqualTo(1);
        } finally {
            source.release();
        }

        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.writeBytes("fff0\1".getBytes(StandardCharsets.US_ASCII));
        expected.write(payload, 0, firstPayloadLength);
        expected.writeBytes("0008\1bcd".getBytes(StandardCharsets.US_ASCII));
        assertThat(bytes.toByteArray()).containsExactly(expected.toByteArray());
    }

    @Test
    void splitsReadableBytesWithoutMovingOrReleasingTheBorrowedBuffer() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStreamBufferedByteOutput wire = new OutputStreamBufferedByteOutput(bytes);
        GitPktLineOutput output = new GitPktLineOutput(wire, SideBand.DATA, 9);
        ByteBuf source = Unpooled.directBuffer(10, 10);
        try {
            source.writeBytes("xxabcdefyy".getBytes(StandardCharsets.US_ASCII));
            source.setIndex(2, 8);
            output.write(source.asReadOnly());
            assertThat(source.readerIndex()).isEqualTo(2);
            assertThat(source.writerIndex()).isEqualTo(8);
            assertThat(source.refCnt()).isEqualTo(1);
            GitPktLine.Control.FLUSH.writeTo(wire);
            assertThat(bytes.toByteArray()).isEqualTo("0009\1abcd0007\1ef0000".getBytes(StandardCharsets.US_ASCII));
        } finally {
            source.release();
        }
    }

    @Test
    void emptyWritesDoNotEmitPacketsAndInvalidLimitsAreRejected() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStreamBufferedByteOutput wire = new OutputStreamBufferedByteOutput(bytes);
        new GitPktLineOutput(wire, SideBand.DATA, 1000).write(Unpooled.EMPTY_BUFFER);
        assertThat(bytes.size()).isZero();
        assertThatThrownBy(() -> new GitPktLineOutput(wire, SideBand.DATA, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GitPktLineOutput(wire, SideBand.DATA, 65521))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
