package pro.deta.orion.git.parser.wire.receivepack;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.wire.GitWireError;
import pro.deta.orion.git.parser.wire.GitWireException;
import pro.deta.orion.git.parser.wire.pkt.GitPktLineWriter;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReceivePackCommandParserTest {
    private static final String NULL_ID = "0".repeat(40);
    private static final String SHA1_A = "a".repeat(40);
    private static final String SHA1_B = "b".repeat(40);
    private static final String SHA1_C = "c".repeat(40);

    private final GitPktLineWriter pktLineWriter = new GitPktLineWriter(UnpooledByteBufAllocator.DEFAULT);
    private final ReceivePackCommandParser parser = new ReceivePackCommandParser();

    @Test
    void parsesSingleCreateCommandWithCapabilities() {
        ByteBuf input = packets(
                data(NULL_ID + " " + SHA1_A + " refs/heads/main\0report-status side-band-64k"),
                flush());

        try {
            ReceivePackCommandSection section = parser.read(input);

            assertThat(section.commands()).hasSize(1);
            ReceivePackCommand cmd = section.commands().get(0);
            assertThat(cmd.oldId()).isEqualTo(NULL_ID);
            assertThat(cmd.newId()).isEqualTo(SHA1_A);
            assertThat(cmd.refName()).isEqualTo("refs/heads/main");
            assertThat(cmd.isCreate()).isTrue();
            assertThat(cmd.isUpdate()).isFalse();
            assertThat(section.clientCapabilities().names())
                    .containsExactly("report-status", "side-band-64k");
            assertThat(input.isReadable()).isFalse();
        } finally {
            input.release();
        }
    }

    @Test
    void parsesSingleUpdateCommandWithCapabilities() {
        ByteBuf input = packets(
                data(SHA1_A + " " + SHA1_B + " refs/heads/main\0report-status"),
                flush());

        try {
            ReceivePackCommandSection section = parser.read(input);

            assertThat(section.commands()).hasSize(1);
            ReceivePackCommand cmd = section.commands().get(0);
            assertThat(cmd.oldId()).isEqualTo(SHA1_A);
            assertThat(cmd.newId()).isEqualTo(SHA1_B);
            assertThat(cmd.isCreate()).isFalse();
            assertThat(cmd.isUpdate()).isTrue();
        } finally {
            input.release();
        }
    }

    @Test
    void parsesMultipleCommandsFirstLineHasCapabilities() {
        ByteBuf input = packets(
                data(NULL_ID + " " + SHA1_A + " refs/heads/main\0report-status"),
                data(NULL_ID + " " + SHA1_B + " refs/heads/feature"),
                flush());

        try {
            ReceivePackCommandSection section = parser.read(input);

            assertThat(section.commands()).hasSize(2);
            assertThat(section.commands().get(0).refName()).isEqualTo("refs/heads/main");
            assertThat(section.commands().get(1).refName()).isEqualTo("refs/heads/feature");
            assertThat(section.clientCapabilities().names()).containsExactly("report-status");
        } finally {
            input.release();
        }
    }

    @Test
    void parsesCommandWithNoCapabilities() {
        ByteBuf input = packets(
                data(NULL_ID + " " + SHA1_A + " refs/heads/main"),
                flush());

        try {
            ReceivePackCommandSection section = parser.read(input);

            assertThat(section.commands()).hasSize(1);
            assertThat(section.clientCapabilities().asList()).isEmpty();
        } finally {
            input.release();
        }
    }

    @Test
    void rejectsMalformedCommandLineTooShort() {
        ByteBuf input = packets(
                data("abc refs/heads/main"),
                flush());

        try {
            assertThatThrownBy(() -> parser.read(input))
                    .isInstanceOfSatisfying(GitWireException.class, error ->
                            assertThat(error.error().kind())
                                    .isEqualTo(GitWireError.Kind.INVALID_RECEIVE_PACK_COMMAND));
        } finally {
            input.release();
        }
    }

    @Test
    void rejectsMalformedObjectIdNonHex() {
        String invalidId = "z" + "a".repeat(39);
        ByteBuf input = packets(
                data(invalidId + " " + SHA1_A + " refs/heads/main"),
                flush());

        try {
            assertThatThrownBy(() -> parser.read(input))
                    .isInstanceOfSatisfying(GitWireException.class, error ->
                            assertThat(error.error().kind())
                                    .isEqualTo(GitWireError.Kind.INVALID_RECEIVE_PACK_COMMAND));
        } finally {
            input.release();
        }
    }

    @Test
    void rejectsMalformedObjectIdWrongLength() {
        String shortId = "a".repeat(39);
        ByteBuf input = packets(
                data(shortId + " " + SHA1_A + " refs/heads/main"),
                flush());

        try {
            assertThatThrownBy(() -> parser.read(input))
                    .isInstanceOfSatisfying(GitWireException.class, error ->
                            assertThat(error.error().kind())
                                    .isEqualTo(GitWireError.Kind.INVALID_RECEIVE_PACK_COMMAND));
        } finally {
            input.release();
        }
    }

    @Test
    void parsesDeleteCommand() {
        ByteBuf input = packets(
                data(SHA1_A + " " + NULL_ID + " refs/heads/main\0report-status"),
                flush());

        try {
            ReceivePackCommandSection section = parser.read(input);

            assertThat(section.commands()).hasSize(1);
            ReceivePackCommand command = section.commands().get(0);
            assertThat(command.isDelete()).isTrue();
            assertThat(command.isCreate()).isFalse();
            assertThat(command.isUpdate()).isFalse();
        } finally {
            input.release();
        }
    }

    @Test
    void parsesTagRefUpdate() {
        ByteBuf input = packets(
                data(NULL_ID + " " + SHA1_A + " refs/tags/v1.0\0report-status"),
                flush());

        try {
            ReceivePackCommandSection section = parser.read(input);

            assertThat(section.commands()).hasSize(1);
            assertThat(section.commands().get(0).refName()).isEqualTo("refs/tags/v1.0");
            assertThat(section.commands().get(0).isCreate()).isTrue();
        } finally {
            input.release();
        }
    }

    @Test
    void parsesNonHeadRefUnderRefsNamespace() {
        ByteBuf input = packets(
                data(NULL_ID + " " + SHA1_A + " refs/remotes/origin/main\0report-status"),
                flush());

        try {
            ReceivePackCommandSection section = parser.read(input);

            assertThat(section.commands()).hasSize(1);
            assertThat(section.commands().get(0).refName()).isEqualTo("refs/remotes/origin/main");
        } finally {
            input.release();
        }
    }

    @Test
    void rejectsDuplicateRef() {
        ByteBuf input = packets(
                data(NULL_ID + " " + SHA1_A + " refs/heads/main\0report-status"),
                data(NULL_ID + " " + SHA1_B + " refs/heads/main"),
                flush());

        try {
            assertThatThrownBy(() -> parser.read(input))
                    .isInstanceOfSatisfying(GitWireException.class, error -> {
                        assertThat(error.error().kind())
                                .isEqualTo(GitWireError.Kind.INVALID_RECEIVE_PACK_COMMAND);
                        assertThat(error.getMessage()).contains("Duplicate");
                    });
        } finally {
            input.release();
        }
    }

    @Test
    void rejectsCommandListOverLimit() {
        ReceivePackCommandParser limitedParser = new ReceivePackCommandParser(2);
        ByteBuf input = packets(
                data(NULL_ID + " " + SHA1_A + " refs/heads/a\0report-status"),
                data(NULL_ID + " " + SHA1_B + " refs/heads/b"),
                data(NULL_ID + " " + SHA1_C + " refs/heads/c"),
                flush());

        try {
            assertThatThrownBy(() -> limitedParser.read(input))
                    .isInstanceOfSatisfying(GitWireException.class, error -> {
                        assertThat(error.error().kind())
                                .isEqualTo(GitWireError.Kind.INVALID_RECEIVE_PACK_COMMAND);
                        assertThat(error.getMessage()).contains("limit");
                    });
        } finally {
            input.release();
        }
    }

    @Test
    void rejectsRefNameWithDotDot() {
        ByteBuf input = packets(
                data(NULL_ID + " " + SHA1_A + " refs/heads/foo..bar\0report-status"),
                flush());

        try {
            assertThatThrownBy(() -> parser.read(input))
                    .isInstanceOfSatisfying(GitWireException.class, error ->
                            assertThat(error.error().kind())
                                    .isEqualTo(GitWireError.Kind.INVALID_RECEIVE_PACK_COMMAND));
        } finally {
            input.release();
        }
    }

    @Test
    void rejectsRefNameEndingWithDotLock() {
        ByteBuf input = packets(
                data(NULL_ID + " " + SHA1_A + " refs/heads/main.lock\0report-status"),
                flush());

        try {
            assertThatThrownBy(() -> parser.read(input))
                    .isInstanceOfSatisfying(GitWireException.class, error ->
                            assertThat(error.error().kind())
                                    .isEqualTo(GitWireError.Kind.INVALID_RECEIVE_PACK_COMMAND));
        } finally {
            input.release();
        }
    }

    @Test
    void rejectsMissingFlushPacket() {
        ByteBuf input = data(NULL_ID + " " + SHA1_A + " refs/heads/main\0report-status");

        try {
            assertThatThrownBy(() -> parser.read(input))
                    .isInstanceOfSatisfying(GitWireException.class, error ->
                            assertThat(error.error().kind())
                                    .isEqualTo(GitWireError.Kind.INVALID_RECEIVE_PACK_COMMAND));
        } finally {
            input.release();
        }
    }

    @Test
    void acceptsUpperCaseHexInObjectId() {
        String upperCaseId = "A".repeat(40);
        ByteBuf input = packets(
                data(upperCaseId + " " + SHA1_A + " refs/heads/main"),
                flush());

        try {
            ReceivePackCommandSection section = parser.read(input);
            assertThat(section.commands().get(0).oldId()).isEqualTo(upperCaseId);
        } finally {
            input.release();
        }
    }

    @Test
    void rejectsRefOutsideRefsNamespace() {
        ByteBuf input = packets(
                data(NULL_ID + " " + SHA1_A + " HEAD\0report-status"),
                flush());

        try {
            assertThatThrownBy(() -> parser.read(input))
                    .isInstanceOfSatisfying(GitWireException.class, error ->
                            assertThat(error.error().kind())
                                    .isEqualTo(GitWireError.Kind.INVALID_RECEIVE_PACK_COMMAND));
        } finally {
            input.release();
        }
    }

    private ByteBuf packets(ByteBuf... packets) {
        ByteBuf input = Unpooled.buffer();
        for (ByteBuf packet : packets) {
            try {
                input.writeBytes(packet, packet.readerIndex(), packet.readableBytes());
            } finally {
                packet.release();
            }
        }
        return input;
    }

    private ByteBuf data(String line) {
        return pktLineWriter.writeTextLine(line);
    }

    private ByteBuf flush() {
        return pktLineWriter.writeFlush();
    }

    private static ByteBuf raw(String ascii) {
        ByteBuf input = Unpooled.buffer();
        input.writeBytes(ascii.getBytes(StandardCharsets.US_ASCII));
        return input;
    }
}
