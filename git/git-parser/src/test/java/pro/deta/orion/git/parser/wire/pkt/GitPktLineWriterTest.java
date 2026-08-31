package pro.deta.orion.git.parser.wire.pkt;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

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

    private static String ascii(byte[] packet) {
        return new String(packet, StandardCharsets.US_ASCII);
    }
}
