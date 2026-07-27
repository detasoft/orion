package pro.deta.orion.git.parser.wire.protocolv2;

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

class GitProtocolV2SectionParserTest {
    private final GitPktLineWriter writer = new GitPktLineWriter(UnpooledByteBufAllocator.DEFAULT);

    @Test
    void parsesLsRefsRequestFromProtocolV2Fixture() {
        ByteBuf input = request(
                data("command=ls-refs"),
                delimiter(),
                data("peel"),
                data("symrefs"),
                data("ref-prefix HEAD"),
                data("ref-prefix refs/heads/"),
                data("ref-prefix refs/tags/"),
                flush());

        try {
            GitProtocolV2Request request = GitProtocolV2SectionParser.read(input);

            assertThat(request.command()).isEqualTo("ls-refs");
            assertThat(request.capabilities()).isEmpty();
            assertThat(request.arguments()).extracting(GitProtocolV2Line::rawLine)
                    .containsExactly(
                            "peel",
                            "symrefs",
                            "ref-prefix HEAD",
                            "ref-prefix refs/heads/",
                            "ref-prefix refs/tags/");
            assertThat(request.terminal()).isEqualTo(GitProtocolV2Request.Terminal.FLUSH);
            assertThat(request.protocolError()).isEmpty();
            assertThat(input.isReadable()).isFalse();
        } finally {
            input.release();
        }
    }

    @Test
    void parsesFetchRequestWithCommandCapabilitiesAndArguments() {
        ByteBuf input = request(
                data("command=fetch"),
                data("agent=git/2.42.0"),
                data("object-format=sha1"),
                delimiter(),
                data("thin-pack"),
                data("ofs-delta"),
                data("want 1111111111111111111111111111111111111111"),
                data("have 2222222222222222222222222222222222222222"),
                data("done"),
                flush());

        try {
            GitProtocolV2Request request = GitProtocolV2SectionParser.read(input);

            assertThat(request.command()).isEqualTo("fetch");
            assertThat(request.capabilities()).extracting(GitProtocolV2Line::rawLine)
                    .containsExactly("agent=git/2.42.0", "object-format=sha1");
            assertThat(request.arguments()).extracting(GitProtocolV2Line::rawLine)
                    .containsExactly(
                            "thin-pack",
                            "ofs-delta",
                            "want 1111111111111111111111111111111111111111",
                            "have 2222222222222222222222222222222222222222",
                            "done");
            assertThat(request.terminal()).isEqualTo(GitProtocolV2Request.Terminal.FLUSH);
            assertThat(request.protocolError()).isEmpty();
        } finally {
            input.release();
        }
    }

    @Test
    void stopsAtResponseEndPacketAndLeavesFollowingBytesUnread() {
        ByteBuf input = request(
                data("command=ls-refs"),
                delimiter(),
                data("ref-prefix HEAD"),
                responseEnd(),
                data("command=fetch"));

        try {
            GitProtocolV2Request request = GitProtocolV2SectionParser.read(input);

            assertThat(request.command()).isEqualTo("ls-refs");
            assertThat(request.arguments()).extracting(GitProtocolV2Line::rawLine)
                    .containsExactly("ref-prefix HEAD");
            assertThat(request.terminal()).isEqualTo(GitProtocolV2Request.Terminal.RESPONSE_END);
            assertThat(input.isReadable()).isTrue();
        } finally {
            input.release();
        }
    }

    @Test
    void parsesProtocolErrorPacket() {
        ByteBuf input = request(data("ERR unsupported command"));

        try {
            GitProtocolV2Request request = GitProtocolV2SectionParser.read(input);

            assertThat(request.command()).isEmpty();
            assertThat(request.capabilities()).isEmpty();
            assertThat(request.arguments()).isEmpty();
            assertThat(request.terminal()).isEqualTo(GitProtocolV2Request.Terminal.ERROR);
            assertThat(request.protocolError()).contains("unsupported command");
        } finally {
            input.release();
        }
    }

    @Test
    void rejectsArgumentBeforeDelimiter() {
        ByteBuf input = request(
                data("command=fetch"),
                data("want 1111111111111111111111111111111111111111"),
                flush());

        try {
            assertThatThrownBy(() -> GitProtocolV2SectionParser.read(input))
                    .isInstanceOfSatisfying(GitWireException.class, error -> assertThat(error.error())
                            .isEqualTo(new GitWireError(
                                    GitWireError.Kind.INVALID_PROTOCOL_V2_REQUEST,
                                    GitWireError.Phase.STRUCTURED_PAYLOAD,
                                    1,
                                    22,
                                    "Protocol v2 arguments must follow a delimiter packet")));
        } finally {
            input.release();
        }
    }

    @Test
    void rejectsDelimiterBeforeCommandAsWireError() {
        ByteBuf input = request(
                delimiter(),
                data("command=fetch"),
                flush());

        try {
            assertThatThrownBy(() -> GitProtocolV2SectionParser.read(input))
                    .isInstanceOfSatisfying(GitWireException.class, error -> assertThat(error.error())
                            .isEqualTo(new GitWireError(
                                    GitWireError.Kind.INVALID_PROTOCOL_V2_REQUEST,
                                    GitWireError.Phase.STRUCTURED_PAYLOAD,
                                    0,
                                    0,
                                    "Protocol v2 delimiter cannot appear before command packet")));
        } finally {
            input.release();
        }
    }

    @Test
    void rejectsReservedPktLineLength() {
        ByteBuf input = raw("0003");

        try {
            assertThatThrownBy(() -> GitProtocolV2SectionParser.read(input))
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

    @Test
    void rejectsPktLineLengthAboveGitLimit() {
        ByteBuf input = raw("ffff");

        try {
            assertThatThrownBy(() -> GitProtocolV2SectionParser.read(input))
                    .isInstanceOfSatisfying(GitWireException.class, error -> assertThat(error.error())
                            .isEqualTo(new GitWireError(
                                    GitWireError.Kind.LENGTH_EXCEEDS_LIMIT,
                                    GitWireError.Phase.CONTROL_HEADER,
                                    0,
                                    0,
                                    "Pkt-line length exceeds Git pkt-line limit")));
        } finally {
            input.release();
        }
    }

    @Test
    void rejectsIncompletePktLineHeader() {
        ByteBuf input = raw("00");

        try {
            assertThatThrownBy(() -> GitProtocolV2SectionParser.read(input))
                    .isInstanceOfSatisfying(GitWireException.class, error -> assertThat(error.error())
                            .isEqualTo(new GitWireError(
                                    GitWireError.Kind.INCOMPLETE_HEADER,
                                    GitWireError.Phase.CONTROL_HEADER,
                                    0,
                                    0,
                                    "Incomplete pkt-line header")));
        } finally {
            input.release();
        }
    }

    @Test
    void rejectsTruncatedPktLinePayload() {
        ByteBuf input = raw("0010command");

        try {
            assertThatThrownBy(() -> GitProtocolV2SectionParser.read(input))
                    .isInstanceOfSatisfying(GitWireException.class, error -> assertThat(error.error())
                            .isEqualTo(new GitWireError(
                                    GitWireError.Kind.INCOMPLETE_PAYLOAD,
                                    GitWireError.Phase.STRUCTURED_PAYLOAD,
                                    0,
                                    4,
                                    "Incomplete pkt-line payload")));
        } finally {
            input.release();
        }
    }

    @Test
    void reportsPacketErrorOffsetsRelativeToRequestStartWhenInputReaderIndexIsShifted() {
        ByteBuf input = shiftedRequest(request(
                data("command=fetch"),
                data("want 1111111111111111111111111111111111111111"),
                flush()));

        try {
            assertThatThrownBy(() -> GitProtocolV2SectionParser.read(input))
                    .isInstanceOfSatisfying(GitWireException.class, error -> assertThat(error.error())
                            .isEqualTo(new GitWireError(
                                    GitWireError.Kind.INVALID_PROTOCOL_V2_REQUEST,
                                    GitWireError.Phase.STRUCTURED_PAYLOAD,
                                    1,
                                    22,
                                    "Protocol v2 arguments must follow a delimiter packet")));
        } finally {
            input.release();
        }
    }

    @Test
    void reportsEndOffsetRelativeToRequestStartWhenInputReaderIndexIsShifted() {
        ByteBuf input = shiftedRequest(request(data("command=fetch")));

        try {
            assertThatThrownBy(() -> GitProtocolV2SectionParser.read(input))
                    .isInstanceOfSatisfying(GitWireException.class, error -> assertThat(error.error())
                            .isEqualTo(new GitWireError(
                                    GitWireError.Kind.INVALID_PROTOCOL_V2_REQUEST,
                                    GitWireError.Phase.STRUCTURED_PAYLOAD,
                                    GitWireError.UNKNOWN_INDEX,
                                    18,
                                    "Protocol v2 request ended before a terminal packet")));
        } finally {
            input.release();
        }
    }

    private ByteBuf request(ByteBuf... packets) {
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

    private ByteBuf shiftedRequest(ByteBuf request) {
        ByteBuf input = Unpooled.buffer();
        try {
            input.writeBytes(new byte[]{'x', 'y', 'z'});
            input.writeBytes(request, request.readerIndex(), request.readableBytes());
            input.readerIndex(3);
            return input;
        } finally {
            request.release();
        }
    }

    private ByteBuf data(String line) {
        return writer.writeTextLine(line);
    }

    private ByteBuf delimiter() {
        return writer.writeDelimiter();
    }

    private ByteBuf flush() {
        return writer.writeFlush();
    }

    private ByteBuf responseEnd() {
        return writer.writeResponseEnd();
    }

    private static ByteBuf raw(String ascii) {
        ByteBuf input = Unpooled.buffer();
        input.writeBytes(ascii.getBytes(StandardCharsets.US_ASCII));
        return input;
    }
}
