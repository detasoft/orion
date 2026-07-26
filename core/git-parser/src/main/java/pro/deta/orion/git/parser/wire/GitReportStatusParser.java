package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class GitReportStatusParser {
    private static final String UNPACK_PREFIX = "unpack ";
    private static final String OK_PREFIX = "ok ";
    private static final String NG_PREFIX = "ng ";

    private GitReportStatusParser() {
    }

    public static GitReportStatus read(ByteBuf input) {
        Objects.requireNonNull(input, "input");
        ParserState state = new ParserState();
        long packetIndex = 0;
        while (input.isReadable()) {
            GitPktLineReader.Packet packet = GitPktLineReader.read(input, packetIndex);
            packetIndex++;
            switch (packet.kind()) {
                case FLUSH -> {
                    return state.toStatus(packet.packetIndex(), packet.byteOffset());
                }
                case DELIMITER -> throw semanticError(
                        GitWireError.Kind.UNEXPECTED_PACKET,
                        packet.packetIndex(),
                        packet.byteOffset(),
                        "Report-status must not contain delimiter packets");
                case RESPONSE_END -> throw semanticError(
                        GitWireError.Kind.UNEXPECTED_PACKET,
                        packet.packetIndex(),
                        packet.byteOffset(),
                        "Report-status must not contain response-end packets");
                case DATA -> state.acceptData(packet.payload(), packet.packetIndex(), packet.byteOffset());
            }
        }
        throw semanticError(
                GitWireError.Kind.UNEXPECTED_PACKET,
                GitWireError.UNKNOWN_INDEX,
                input.readerIndex(),
                "Report-status ended before flush packet");
    }

    private static GitWireException semanticError(
            GitWireError.Kind kind,
            long packetIndex,
            long byteOffset,
            String message) {
        return GitWireException.of(kind, GitWireError.Phase.STRUCTURED_PAYLOAD, packetIndex, byteOffset, message);
    }

    private static final class ParserState {
        private final List<GitReportStatusRef> refs = new ArrayList<>();
        private boolean unpackSeen;
        private boolean unpackOk;
        private String unpackError;

        private void acceptData(String line, long packetIndex, long byteOffset) {
            if (!unpackSeen) {
                acceptFirstLine(line, packetIndex, byteOffset);
                return;
            }
            if (line.startsWith(UNPACK_PREFIX)) {
                throw semanticError(
                        GitWireError.Kind.DUPLICATE_UNPACK_STATUS,
                        packetIndex,
                        byteOffset,
                        "Report-status must contain only one unpack status");
            }
            if (line.startsWith(OK_PREFIX)) {
                refs.add(parseAcceptedRef(line.substring(OK_PREFIX.length()), packetIndex, byteOffset));
                return;
            }
            if (line.startsWith(NG_PREFIX)) {
                refs.add(parseRejectedRef(line.substring(NG_PREFIX.length()), packetIndex, byteOffset));
                return;
            }
            throw semanticError(
                    GitWireError.Kind.INVALID_REPORT_STATUS_LINE,
                    packetIndex,
                    byteOffset,
                    "Unknown report-status line");
        }

        private void acceptFirstLine(String line, long packetIndex, long byteOffset) {
            if (!line.startsWith(UNPACK_PREFIX)) {
                throw semanticError(
                        GitWireError.Kind.MISSING_UNPACK_STATUS,
                        packetIndex,
                        byteOffset,
                        "Report-status must start with unpack status");
            }
            String value = line.substring(UNPACK_PREFIX.length());
            if (value.equals("ok")) {
                unpackSeen = true;
                unpackOk = true;
                return;
            }
            if (value.isBlank()) {
                throw semanticError(
                        GitWireError.Kind.INVALID_REPORT_STATUS_LINE,
                        packetIndex,
                        byteOffset,
                        "Unpack error reason must not be blank");
            }
            unpackSeen = true;
            unpackOk = false;
            unpackError = value;
        }

        private GitReportStatus toStatus(long packetIndex, long byteOffset) {
            if (!unpackSeen) {
                throw semanticError(
                        GitWireError.Kind.MISSING_UNPACK_STATUS,
                        packetIndex,
                        byteOffset,
                        "Report-status must include unpack status");
            }
            if (unpackOk) {
                return GitReportStatus.unpackOk(refs);
            }
            return GitReportStatus.unpackError(unpackError, refs);
        }

        private static GitReportStatusRef parseAcceptedRef(String payload, long packetIndex, long byteOffset) {
            validateRefName(payload, packetIndex, byteOffset);
            return GitReportStatusRef.ok(payload);
        }

        private static GitReportStatusRef parseRejectedRef(String payload, long packetIndex, long byteOffset) {
            int reasonStart = payload.indexOf(' ');
            if (reasonStart < 0 || reasonStart == payload.length() - 1) {
                throw semanticError(
                        GitWireError.Kind.INVALID_REPORT_STATUS_LINE,
                        packetIndex,
                        byteOffset,
                        "Rejected ref status must include a reason");
            }
            String refName = payload.substring(0, reasonStart);
            String reason = payload.substring(reasonStart + 1);
            validateRefName(refName, packetIndex, byteOffset);
            if (reason.isBlank()) {
                throw semanticError(
                        GitWireError.Kind.INVALID_REPORT_STATUS_LINE,
                        packetIndex,
                        byteOffset,
                        "Rejected ref status must include a reason");
            }
            return GitReportStatusRef.ng(refName, reason);
        }

        private static void validateRefName(String refName, long packetIndex, long byteOffset) {
            if (refName.isBlank()) {
                throw semanticError(
                        GitWireError.Kind.INVALID_REPORT_STATUS_LINE,
                        packetIndex,
                        byteOffset,
                        "Ref status must include a ref name");
            }
            for (int index = 0; index < refName.length(); index++) {
                if (Character.isWhitespace(refName.charAt(index))) {
                    throw semanticError(
                            GitWireError.Kind.INVALID_REPORT_STATUS_LINE,
                            packetIndex,
                            byteOffset,
                            "Ref name must not contain whitespace");
                }
            }
        }
    }
}
