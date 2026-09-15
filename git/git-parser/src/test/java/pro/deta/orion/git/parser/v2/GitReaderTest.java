package pro.deta.orion.git.parser.v2;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.wire.control.ControlState;
import pro.deta.orion.net.io.InputStreamBufferedByteInput;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

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
                assertThat(reader.readText(data(payload.length))).isEqualTo(expected);
                assertThat(input.readUnsignedByte()).isEqualTo('N');
            }
        }
    }

    @Test
    void rejectsMalformedUtf8AfterConsumingExactlyThePayload() throws Exception {
        for (byte[] payload : new byte[][]{{(byte) 0xc3, 0x28}, {(byte) 0xe2, (byte) 0x82}, {(byte) 0x80, '\n'}}) {
            try (var input = inputWithMarker(payload)) {
                GitReader reader = new GitReader(input);
                assertThatThrownBy(() -> reader.readText(data(payload.length)))
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
                assertThatThrownBy(() -> reader.readText(data(payload.length)))
                        .isInstanceOf(IOException.class).hasMessageContaining("Control character");
                assertThat(input.readUnsignedByte()).isEqualTo('N');
            }
        }
    }

    private static ControlState data(int length) {
        return new ControlState(ControlState.ControlType.DATA, length + ControlState.PKT_LINE_HEADER_SIZE);
    }

    private static InputStreamBufferedByteInput inputWithMarker(byte[] payload) {
        byte[] bytes = Arrays.copyOf(payload, payload.length + 1);
        bytes[payload.length] = 'N';
        return new InputStreamBufferedByteInput(new ByteArrayInputStream(bytes) {
            @Override
            public synchronized int read(byte[] target, int offset, int length) {
                return super.read(target, offset, Math.min(length, 2));
            }
        });
    }
}
