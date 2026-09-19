package pro.deta.orion.git.parser.v2.proto;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.data.GitProtocolVersion;
import pro.deta.orion.git.parser.v2.data.GitTransport;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitProtocolReaderTest {
    @Test
    void readsFragmentedUtf8AndRemovesOnlyTheOptionalFinalLf() throws Exception {
        for (String line : new String[]{"refs/heads/ветка", "refs/heads/ветка\n", "", "\n"}) {
            byte[] payload = line.getBytes(StandardCharsets.UTF_8);
            try (BufferedByteInputV2 input = inputWithMarker(payload)) {
                GitProtocolContext.Reader reader = reader(input);
                String expected = line.endsWith("\n") ? line.substring(0, line.length() - 1) : line;
                assertThat(((GitPktLine.Data) reader.readGitPktLine()).text()).isEqualTo(expected);
                assertThat(input.readUnsignedByte()).isEqualTo('N');
            }
        }
    }

    @Test
    void rejectsMalformedUtf8AfterConsumingExactlyThePayload() throws Exception {
        for (byte[] payload : new byte[][]{{(byte) 0xc3, 0x28}, {(byte) 0xe2, (byte) 0x82}, {(byte) 0x80, '\n'}}) {
            try (BufferedByteInputV2 input = inputWithMarker(payload)) {
                GitProtocolContext.Reader reader = reader(input);
                assertThatThrownBy(() -> ((GitPktLine.Data) reader.readGitPktLine()).text())
                        .isInstanceOf(IOException.class).hasMessageContaining("Invalid UTF-8");
                assertThat(input.readUnsignedByte()).isEqualTo('N');
            }
        }
    }

    @Test
    void rejectsEmbeddedControlsAndASecondTrailingLf() throws Exception {
        for (String line : new String[]{"a\nb", "a\n\n", "a\r\n", "a\tb", "a\0b", "a\u007fb"}) {
            byte[] payload = line.getBytes(StandardCharsets.UTF_8);
            try (BufferedByteInputV2 input = inputWithMarker(payload)) {
                GitProtocolContext.Reader reader = reader(input);
                assertThatThrownBy(() -> ((GitPktLine.Data) reader.readGitPktLine()).text())
                        .isInstanceOf(IOException.class).hasMessageContaining("Control character");
                assertThat(input.readUnsignedByte()).isEqualTo('N');
            }
        }
    }

    @Test
    void controlPacketsHaveNoTextAndDoNotConsumeTheFollowingPacket() throws Exception {
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(
                "0000000100020004".getBytes(StandardCharsets.US_ASCII)))) {
            GitProtocolContext.Reader reader = reader(input);
            for (GitPktLine.Control type : new GitPktLine.Control[]{GitPktLine.Control.FLUSH,
                    GitPktLine.Control.DELIMITER, GitPktLine.Control.RESPONSE_END}) {
                GitPktLine packet = reader.readGitPktLine();
                assertThat(packet).isSameAs(type);
                assertThat(packet).isInstanceOf(GitPktLine.Control.class);
                assertThat(packet.payloadLength()).isZero();
            }
            GitPktLine packet = reader.readGitPktLine();
            assertThat(packet).isInstanceOf(GitPktLine.Data.class);
            assertThat(((GitPktLine.Data) packet).text()).isEmpty();
            assertThat(input.newInputStream().available()).isZero();
        }
    }

    @Test
    void dataContainsRawBytesBeforeAnyTextDecoding() throws Exception {
        byte[] raw = {(byte) 0xff, 0, (byte) 0x80};
        try (BufferedByteInputV2 input = inputWithMarker(raw)) {
            GitPktLine.Data data = (GitPktLine.Data) reader(input).readGitPktLine();
            assertThat(data.content()).containsExactly(raw);
            assertThat(input.readUnsignedByte()).isEqualTo('N');
            assertThatThrownBy(data::text).isInstanceOf(IOException.class);
        }
    }

    @Test
    void optionalPacketReadAllowsOnlyCleanEof() throws Exception {
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(new byte[0]))) {
            assertThat(GitPktLine.readNextFrom(input)).isEmpty();
            assertThatThrownBy(() -> reader(input).readGitPktLine()).isInstanceOf(EOFException.class);
        }
        for (String truncated : new String[]{"0", "000", "0006a"}) {
            try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(
                    truncated.getBytes(StandardCharsets.US_ASCII)))) {
                assertThatThrownBy(() -> GitPktLine.readNextFrom(input)).isInstanceOf(EOFException.class);
            }
        }
    }

    private static GitProtocolContext.Reader reader(BufferedByteInputV2 input) {
        return new GitProtocolContext(input, new OutputStreamBufferedByteOutput(OutputStream.nullOutputStream()),
                GitProtocolVersion.V2, GitTransport.SSH).reader();
    }

    private static BufferedByteInputV2 inputWithMarker(byte[] payload) {
        byte[] header = "%04x".formatted(payload.length + 4).getBytes(StandardCharsets.US_ASCII);
        byte[] bytes = new byte[header.length + payload.length + 1];
        System.arraycopy(header, 0, bytes, 0, header.length);
        System.arraycopy(payload, 0, bytes, header.length, payload.length);
        bytes[bytes.length - 1] = 'N';
        return new BufferedByteInputV2(new ByteArrayInputStream(bytes) {
            @Override
            public synchronized int read(byte[] target, int offset, int length) {
                return super.read(target, offset, Math.min(length, 2));
            }
        });
    }
}
