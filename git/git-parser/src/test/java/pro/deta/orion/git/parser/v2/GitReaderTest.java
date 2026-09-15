package pro.deta.orion.git.parser.v2;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.net.io.InputStreamBufferedByteInput;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitReaderTest {
    @Test
    void readsFragmentedUtf8AndRemovesOnlyTheOptionalFinalLf() throws Exception {
        for (String line : new String[]{"refs/heads/ветка", "refs/heads/ветка\n", "", "\n"}) {
            byte[] payload = line.getBytes(StandardCharsets.UTF_8);
            try (var input = inputWithMarker(payload)) {
                GitReader reader = new GitReader(input);
                String expected = line.endsWith("\n") ? line.substring(0, line.length() - 1) : line;
                assertThat(((GitPktLine.Data) reader.readPacket()).text()).isEqualTo(expected);
                assertThat(input.readUnsignedByte()).isEqualTo('N');
            }
        }
    }

    @Test
    void rejectsMalformedUtf8AfterConsumingExactlyThePayload() throws Exception {
        for (byte[] payload : new byte[][]{{(byte) 0xc3, 0x28}, {(byte) 0xe2, (byte) 0x82}, {(byte) 0x80, '\n'}}) {
            try (var input = inputWithMarker(payload)) {
                GitReader reader = new GitReader(input);
                assertThatThrownBy(() -> ((GitPktLine.Data) reader.readPacket()).text())
                        .isInstanceOf(IOException.class).hasMessageContaining("Invalid UTF-8");
                assertThat(input.readUnsignedByte()).isEqualTo('N');
            }
        }
    }

    @Test
    void rejectsEmbeddedControlsAndASecondTrailingLf() throws Exception {
        for (String line : new String[]{"a\nb", "a\n\n", "a\r\n", "a\tb", "a\0b", "a\u007fb"}) {
            byte[] payload = line.getBytes(StandardCharsets.UTF_8);
            try (var input = inputWithMarker(payload)) {
                GitReader reader = new GitReader(input);
                assertThatThrownBy(() -> ((GitPktLine.Data) reader.readPacket()).text())
                        .isInstanceOf(IOException.class).hasMessageContaining("Control character");
                assertThat(input.readUnsignedByte()).isEqualTo('N');
            }
        }
    }

    @Test
    void controlPacketsHaveNoTextAndDoNotConsumeTheFollowingPacket() throws Exception {
        try (var input = new InputStreamBufferedByteInput(new ByteArrayInputStream(
                "0000000100020004".getBytes(StandardCharsets.US_ASCII)))) {
            var reader = new GitReader(input);
            for (var type : new GitPktLine.Control[]{GitPktLine.Control.FLUSH,
                    GitPktLine.Control.DELIMITER, GitPktLine.Control.RESPONSE_END}) {
                var packet = reader.readPacket();
                assertThat(packet).isSameAs(type);
                assertThat(packet).isInstanceOf(GitPktLine.Control.class);
                assertThat(packet.payloadLength()).isZero();
            }
            var packet = reader.readPacket();
            assertThat(packet).isInstanceOf(GitPktLine.Data.class);
            assertThat(((GitPktLine.Data) packet).text()).isEmpty();
            assertThat(input.available()).isZero();
        }
    }

    @Test
    void dataContainsRawBytesBeforeAnyTextDecoding() throws Exception {
        byte[] raw = {(byte) 0xff, 0, (byte) 0x80};
        try (var input = inputWithMarker(raw)) {
            var data = (GitPktLine.Data) new GitReader(input).readPacket();
            assertThat(data.content()).containsExactly(raw);
            assertThat(input.readUnsignedByte()).isEqualTo('N');
            assertThatThrownBy(data::text).isInstanceOf(IOException.class);
        }
    }

    @Test
    void optionalPacketReadAllowsOnlyCleanEof() throws Exception {
        try (var input = new InputStreamBufferedByteInput(new ByteArrayInputStream(new byte[0]))) {
            assertThat(GitPktLine.readNextFrom(input)).isEmpty();
            assertThatThrownBy(() -> new GitReader(input).readPacket()).isInstanceOf(EOFException.class);
        }
        for (String truncated : new String[]{"0", "000", "0006a"}) {
            try (var input = new InputStreamBufferedByteInput(new ByteArrayInputStream(
                    truncated.getBytes(StandardCharsets.US_ASCII)))) {
                assertThatThrownBy(() -> GitPktLine.readNextFrom(input)).isInstanceOf(EOFException.class);
            }
        }
    }

    private static InputStreamBufferedByteInput inputWithMarker(byte[] payload) {
        byte[] header = "%04x".formatted(payload.length + 4).getBytes(StandardCharsets.US_ASCII);
        byte[] bytes = new byte[header.length + payload.length + 1];
        System.arraycopy(header, 0, bytes, 0, header.length);
        System.arraycopy(payload, 0, bytes, header.length, payload.length);
        bytes[bytes.length - 1] = 'N';
        return new InputStreamBufferedByteInput(new ByteArrayInputStream(bytes) {
            @Override
            public synchronized int read(byte[] target, int offset, int length) {
                return super.read(target, offset, Math.min(length, 2));
            }
        });
    }
}
