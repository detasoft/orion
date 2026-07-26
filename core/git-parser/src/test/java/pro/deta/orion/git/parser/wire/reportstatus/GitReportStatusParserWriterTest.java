package pro.deta.orion.git.parser.wire.reportstatus;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.wire.GitWireError;
import pro.deta.orion.git.parser.wire.GitWireException;
import pro.deta.orion.git.parser.wire.pkt.GitPktLineWriter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitReportStatusParserWriterTest {
    private final GitPktLineWriter pktLineWriter = new GitPktLineWriter(UnpooledByteBufAllocator.DEFAULT);
    private final GitReportStatusWriter reportStatusWriter = new GitReportStatusWriter();

    @Test
    void parsesSuccessfulUnpackWithOkRefs() {
        ByteBuf input = packets(
                data("unpack ok"),
                data("ok refs/heads/main"),
                data("ok refs/tags/v1.0.0"),
                flush());

        try {
            GitReportStatus status = GitReportStatusParser.read(input);

            assertThat(status.unpackOk()).isTrue();
            assertThat(status.unpackError()).isEmpty();
            assertThat(status.refs()).containsExactly(
                    GitReportStatusRef.ok("refs/heads/main"),
                    GitReportStatusRef.ok("refs/tags/v1.0.0"));
            assertThat(input.isReadable()).isFalse();
        } finally {
            input.release();
        }
    }

    @Test
    void parsesUnpackFailureAndRejectedRef() {
        ByteBuf input = packets(
                data("unpack pack exceeds repository limit"),
                data("ng refs/heads/main unpack failed"),
                flush());

        try {
            GitReportStatus status = GitReportStatusParser.read(input);

            assertThat(status.unpackOk()).isFalse();
            assertThat(status.unpackError()).contains("pack exceeds repository limit");
            assertThat(status.refs()).containsExactly(
                    GitReportStatusRef.ng("refs/heads/main", "unpack failed"));
        } finally {
            input.release();
        }
    }

    @Test
    void preservesRejectedRefReasonTextAfterRefName() {
        ByteBuf input = packets(
                data("unpack ok"),
                data("ng refs/heads/main protected branch requires review"),
                flush());

        try {
            GitReportStatus status = GitReportStatusParser.read(input);

            assertThat(status.refs()).containsExactly(
                    GitReportStatusRef.ng("refs/heads/main", "protected branch requires review"));
        } finally {
            input.release();
        }
    }

    @Test
    void writesReportStatusPktLineSequence() {
        GitReportStatus status = GitReportStatus.unpackOk(List.of(
                GitReportStatusRef.ok("refs/heads/main"),
                GitReportStatusRef.ng("refs/heads/protected", "protected branch")));

        List<ByteBuf> packets = reportStatusWriter.write(pktLineWriter, status);
        try {
            assertThat(ascii(packets)).containsExactly(
                    pktLine("unpack ok\n"),
                    pktLine("ok refs/heads/main\n"),
                    pktLine("ng refs/heads/protected protected branch\n"),
                    "0000");
        } finally {
            release(packets);
        }
    }

    @Test
    void rejectsRefStatusBeforeUnpackStatus() {
        ByteBuf input = packets(
                data("ok refs/heads/main"),
                flush());

        try {
            assertThatThrownBy(() -> GitReportStatusParser.read(input))
                    .isInstanceOfSatisfying(GitWireException.class, error -> assertThat(error.error())
                            .isEqualTo(new GitWireError(
                                    GitWireError.Kind.MISSING_UNPACK_STATUS,
                                    GitWireError.Phase.STRUCTURED_PAYLOAD,
                                    0,
                                    4,
                                    "Report-status must start with unpack status")));
        } finally {
            input.release();
        }
    }

    @Test
    void rejectsNgWithoutReason() {
        ByteBuf input = packets(
                data("unpack ok"),
                data("ng refs/heads/main"),
                flush());

        try {
            assertThatThrownBy(() -> GitReportStatusParser.read(input))
                    .isInstanceOfSatisfying(GitWireException.class, error -> assertThat(error.error())
                            .isEqualTo(new GitWireError(
                                    GitWireError.Kind.INVALID_REPORT_STATUS_LINE,
                                    GitWireError.Phase.STRUCTURED_PAYLOAD,
                                    1,
                                    18,
                                    "Rejected ref status must include a reason")));
        } finally {
            input.release();
        }
    }

    @Test
    void rejectsFlushOnlyReportWithMissingUnpackStatus() {
        ByteBuf input = packets(flush());

        try {
            assertThatThrownBy(() -> GitReportStatusParser.read(input))
                    .isInstanceOfSatisfying(GitWireException.class, error -> assertThat(error.error())
                            .isEqualTo(new GitWireError(
                                    GitWireError.Kind.MISSING_UNPACK_STATUS,
                                    GitWireError.Phase.STRUCTURED_PAYLOAD,
                                    0,
                                    0,
                                    "Report-status must include unpack status")));
        } finally {
            input.release();
        }
    }

    @Test
    void rejectsDuplicateUnpackStatus() {
        ByteBuf input = packets(
                data("unpack ok"),
                data("unpack ok"),
                flush());

        try {
            assertThatThrownBy(() -> GitReportStatusParser.read(input))
                    .isInstanceOfSatisfying(GitWireException.class, error -> assertThat(error.error())
                            .isEqualTo(new GitWireError(
                                    GitWireError.Kind.DUPLICATE_UNPACK_STATUS,
                                    GitWireError.Phase.STRUCTURED_PAYLOAD,
                                    1,
                                    18,
                                    "Report-status must contain only one unpack status")));
        } finally {
            input.release();
        }
    }

    @Test
    void rejectsUnpackWithBlankReason() {
        ByteBuf input = packets(
                data("unpack "),
                flush());

        try {
            assertThatThrownBy(() -> GitReportStatusParser.read(input))
                    .isInstanceOfSatisfying(GitWireException.class, error -> assertThat(error.error())
                            .isEqualTo(new GitWireError(
                                    GitWireError.Kind.INVALID_REPORT_STATUS_LINE,
                                    GitWireError.Phase.STRUCTURED_PAYLOAD,
                                    0,
                                    4,
                                    "Unpack error reason must not be blank")));
        } finally {
            input.release();
        }
    }

    @Test
    void rejectsReservedPktLineLength() {
        ByteBuf input = raw("0003");

        try {
            assertThatThrownBy(() -> GitReportStatusParser.read(input))
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
            assertThatThrownBy(() -> GitReportStatusParser.read(input))
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
            assertThatThrownBy(() -> GitReportStatusParser.read(input))
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
        ByteBuf input = raw("0010unpack");

        try {
            assertThatThrownBy(() -> GitReportStatusParser.read(input))
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
    void reportsErrorOffsetsRelativeToReportStatusStartWhenInputReaderIndexIsShifted() {
        ByteBuf input = shiftedInput(
                packets(
                        data("unpack ok"),
                        data("ng refs/heads/main"),
                        flush()));

        try {
            assertThatThrownBy(() -> GitReportStatusParser.read(input))
                    .isInstanceOfSatisfying(GitWireException.class, error -> assertThat(error.error())
                            .isEqualTo(new GitWireError(
                                    GitWireError.Kind.INVALID_REPORT_STATUS_LINE,
                                    GitWireError.Phase.STRUCTURED_PAYLOAD,
                                    1,
                                    18,
                                    "Rejected ref status must include a reason")));
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

    private ByteBuf shiftedInput(ByteBuf reportStatus) {
        ByteBuf input = Unpooled.buffer();
        try {
            input.writeBytes(new byte[]{'x', 'y', 'z'});
            input.writeBytes(reportStatus, reportStatus.readerIndex(), reportStatus.readableBytes());
            input.readerIndex(3);
            return input;
        } finally {
            reportStatus.release();
        }
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

    private static List<String> ascii(List<ByteBuf> packets) {
        List<String> values = new ArrayList<>();
        for (ByteBuf packet : packets) {
            values.add(ascii(packet));
        }
        return List.copyOf(values);
    }

    private static String ascii(ByteBuf packet) {
        byte[] bytes = new byte[packet.readableBytes()];
        packet.getBytes(packet.readerIndex(), bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static String pktLine(String payload) {
        return "%04x%s".formatted(payload.length() + 4, payload);
    }

    private static void release(List<ByteBuf> packets) {
        for (ByteBuf packet : packets) {
            packet.release();
        }
    }
}
