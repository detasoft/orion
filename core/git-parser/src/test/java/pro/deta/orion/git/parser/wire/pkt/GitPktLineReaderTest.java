package pro.deta.orion.git.parser.wire.pkt;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.wire.GitWireError;
import pro.deta.orion.git.parser.wire.GitWireException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitPktLineReaderTest {
    private final GitPktLineWriter writer = new GitPktLineWriter(UnpooledByteBufAllocator.DEFAULT);

    @Test
    void readsHeaderWithoutConsumingInput() {
        ByteBuf input = shiftedInput(writer.writeTextLine("hello"));

        try {
            int readerIndex = input.readerIndex();

            GitPktLineReader.Header header = GitPktLineReader.readHeader(input, 7, 1);

            assertThat(header).isEqualTo(new GitPktLineReader.Header(input.readableBytes(), 7, readerIndex, 2));
            assertThat(input.readerIndex()).isEqualTo(readerIndex);
        } finally {
            input.release();
        }
    }

    @Test
    void reportsIncompleteHeaderRelativeToStartReaderIndex() {
        ByteBuf input = shiftedInput(raw("003"));

        try {
            assertThatThrownBy(() -> GitPktLineReader.readHeader(input, 7, 3))
                    .isInstanceOfSatisfying(GitWireException.class, error -> assertThat(error.error())
                            .isEqualTo(new GitWireError(
                                    GitWireError.Kind.INCOMPLETE_HEADER,
                                    GitWireError.Phase.CONTROL_HEADER,
                                    7,
                                    0,
                                    "Incomplete pkt-line header")));
        } finally {
            input.release();
        }
    }

    @Test
    void readsPacketOffsetsRelativeToStartReaderIndex() {
        ByteBuf input = shiftedInput(writer.writeTextLine("hello"));

        try {
            GitPktLineReader.Packet packet = GitPktLineReader.read(input, 7, 3);

            assertThat(packet).isEqualTo(new GitPktLineReader.Packet(
                    GitPktLineReader.Kind.DATA,
                    "hello",
                    7,
                    4));
            assertThat(input.isReadable()).isFalse();
        } finally {
            input.release();
        }
    }

    @Test
    void reportsPacketErrorsRelativeToStartReaderIndex() {
        ByteBuf input = shiftedInput(raw("0003"));

        try {
            assertThatThrownBy(() -> GitPktLineReader.read(input, 0, 3))
                    .isInstanceOfSatisfying(GitWireException.class, error -> assertThat(error.error())
                            .isEqualTo(new GitWireError(
                                    GitWireError.Kind.RESERVED_LENGTH,
                                    GitWireError.Phase.CONTROL_HEADER,
                                    0,
                                    0,
                                    "Pkt-line length 0003 is reserved")));
        } finally {
            input.release();
        }
    }

    private static ByteBuf shiftedInput(ByteBuf packet) {
        ByteBuf input = Unpooled.buffer();
        try {
            input.writeBytes(new byte[]{'x', 'y', 'z'});
            input.writeBytes(packet, packet.readerIndex(), packet.readableBytes());
            input.readerIndex(3);
            return input;
        } finally {
            packet.release();
        }
    }

    private static ByteBuf raw(String ascii) {
        ByteBuf input = Unpooled.buffer();
        input.writeBytes(ascii.getBytes(StandardCharsets.US_ASCII));
        return input;
    }
}
