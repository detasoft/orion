package pro.deta.orion.git.parser.wire.pkt;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.parser.wire.control.ControlState.MAX_PKT_LINE_LENGTH;

class GitPktLineWriterTest {
    private final GitPktLineWriter writer = new GitPktLineWriter();

    @Test
    void writesDataPacketHeader() {
        assertThat(ascii(writer.writeDataHeader(3))).isEqualTo("0007");
    }

    @Test
    void writesControlPackets() {
        assertThat(ascii(writer.writeFlush())).isEqualTo("0000");
        assertThat(ascii(writer.writeDelimiter())).isEqualTo("0001");
        assertThat(ascii(writer.writeResponseEnd())).isEqualTo("0002");
    }

    @Test
    void rejectsPayloadAboveGitPktLineLimit() {
        assertThatThrownBy(() -> writer.writeDataHeader(MAX_PKT_LINE_LENGTH - 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Pkt-line payload exceeds Git pkt-line limit");
    }

    @Test
    void writesSidebandHeader() {
        assertThat(ascii(writer.writeSidebandHeader(3, 7)))
                .isEqualTo("000c\u0003");
    }

    @Test
    void writesSidebandPacketsAndSplitsAtPktLineLimit() {
        int firstPayloadLength = MAX_PKT_LINE_LENGTH - 5;
        byte[] payload = new byte[firstPayloadLength + 3];
        Arrays.fill(payload, 0, firstPayloadLength, (byte) 'a');
        Arrays.fill(payload, firstPayloadLength, payload.length, (byte) 'b');

        List<byte[]> packets = writer.writeSidebandPackets(1, payload);

        assertThat(packets).hasSize(2);
        assertThat(ascii(Arrays.copyOfRange(packets.getFirst(), 0, 5)))
                .isEqualTo("fff0\u0001");
        assertThat(packets.getFirst()).hasSize(MAX_PKT_LINE_LENGTH);
        assertThat(ascii(Arrays.copyOfRange(packets.get(1), 0, 5)))
                .isEqualTo("0008\u0001");
        assertThat(Arrays.copyOfRange(packets.get(1), 5, 8))
                .containsExactly((byte) 'b', (byte) 'b', (byte) 'b');
    }

    private static String ascii(byte[] packet) {
        return new String(packet, StandardCharsets.US_ASCII);
    }
}
