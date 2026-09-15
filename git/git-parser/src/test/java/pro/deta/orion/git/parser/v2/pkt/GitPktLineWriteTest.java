package pro.deta.orion.git.parser.v2.pkt;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.wire.RecordingBufferedByteOutput;
import pro.deta.orion.net.io.InputStreamBufferedByteInput;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.parser.v2.pkt.GitPktLine.MAX_PKT_LINE_LENGTH;

class GitPktLineWriteTest {
    @Test
    void writesDataWithoutSideBandByDefault() throws Exception {
        var output = new RecordingBufferedByteOutput();
        new GitPktLine.Data("hello".getBytes(StandardCharsets.US_ASCII)).writeTo(output);
        assertThat(output.ascii()).isEqualTo("0009hello");
    }

    @Test
    void writesEachChannelWithoutChangingThePayload() throws Exception {
        byte[] payload = {0, (byte) 0xff, '\n'};
        for (SideBand sideBand : SideBand.values()) {
            var output = new RecordingBufferedByteOutput();
            var packet = new GitPktLine.Data(payload);
            packet.writeTo(output, sideBand);
            boolean hasChannel = sideBand != SideBand.NONE;
            try (var input = new InputStreamBufferedByteInput(new ByteArrayInputStream(output.bytes()))) {
                var read = (GitPktLine.Data) GitPktLine.readNextFrom(input).orElseThrow();
                byte[] expected = switch (sideBand) {
                    case NONE -> payload;
                    case DATA -> new byte[]{1, 0, (byte) 0xff, '\n'};
                    case PROGRESS -> new byte[]{2, 0, (byte) 0xff, '\n'};
                    case ERROR -> new byte[]{3, 0, (byte) 0xff, '\n'};
                };
                assertThat(read.content()).containsExactly(expected);
                assertThat(GitPktLine.readNextFrom(input)).isEmpty();
            }
            assertThat(output.bytes()).hasSize(4 + payload.length + (hasChannel ? 1 : 0));
            assertThat(packet.content()).containsExactly((byte) 0, (byte) 0xff, (byte) '\n');
        }
    }

    @Test
    void keepsControlMarkersUnprefixedForEveryChannel() throws Exception {
        for (SideBand sideBand : SideBand.values()) {
            var output = new RecordingBufferedByteOutput();
            GitPktLine.Control.FLUSH.writeTo(output, sideBand);
            GitPktLine.Control.DELIMITER.writeTo(output, sideBand);
            GitPktLine.Control.RESPONSE_END.writeTo(output, sideBand);
            assertThat(output.ascii()).isEqualTo("000000010002");
        }
    }

    @Test
    void distinguishesEmptyDataFromFlushWithAndWithoutSideBand() throws Exception {
        var output = new RecordingBufferedByteOutput();
        new GitPktLine.Data(new byte[0]).writeTo(output);
        new GitPktLine.Data(new byte[0]).writeTo(output, SideBand.DATA);
        GitPktLine.Control.FLUSH.writeTo(output);
        assertThat(output.ascii()).isEqualTo("00040005\u00010000");
    }

    @Test
    void includesTheChannelInTheLimitAndRejectsOversizeBeforeAnyWrite() throws Exception {
        for (SideBand sideBand : SideBand.values()) {
            int maximumPayload = MAX_PKT_LINE_LENGTH - 4 - (sideBand == SideBand.NONE ? 0 : 1);
            var output = new RecordingBufferedByteOutput();
            new GitPktLine.Data(new byte[maximumPayload]).writeTo(output, sideBand);
            assertThat(output.bytes()).hasSize(MAX_PKT_LINE_LENGTH);
            assertThat(new String(output.bytes(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("fff0");

            var rejectedOutput = new RecordingBufferedByteOutput();
            assertThatThrownBy(() -> new GitPktLine.Data(new byte[maximumPayload + 1])
                    .writeTo(rejectedOutput, sideBand))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Pkt-line payload exceeds Git pkt-line limit");
            assertThat(rejectedOutput.bytes()).isEmpty();
        }
    }
}
