package pro.deta.orion.git.parser.wire.control;

import pro.deta.orion.git.parser.wire.GitPktLineFormatException;
import pro.deta.orion.git.parser.wire.error.GitGeneralException;
import pro.deta.orion.net.io.BufferedByteInput;

import java.io.IOException;
import java.io.EOFException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

import static pro.deta.orion.git.parser.wire.GitNativeUtils.HEX_VALUES;
import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.*;

/**
 * One completely read pkt-line: Data owns its payload, while Control has no payload.
 * readFrom validates the header and reads exactly the declared payload through BufferedByteInput.readBytes.
 * DATA stays binary until text() is explicitly requested; arbitrary pack bytes need not be valid UTF-8.
 * type and lengths are derived from the variant, never independently mutable header state.
 * No stream or reference-counted buffer is retained. EOF during the header or payload is an IOException.
 * readNextFrom permits clean EOF before a packet; partial headers and payloads always fail.
 */
public sealed interface ControlState {
    int PKT_LINE_HEADER_SIZE = 4;
    int MAX_PKT_LINE_LENGTH = 65_520;

    enum ControlType {
        DATA,
        FLUSH,
        DELIMITER,
        RESPONSE_END
    }

    ControlType type();

    default int payloadLength() {
        return this instanceof Data data ? data.content().length : 0;
    }

    default int length() {
        return PKT_LINE_HEADER_SIZE + payloadLength();
    }

    /**
     * Raw payload owned by this packet. content exposes the bytes without another copy.
     * text decodes strict UTF-8, removes one final LF, and rejects embedded ASCII control characters.
     * Empty data remains distinct from FLUSH. Binary consumers use content and do not call text.
     */
    record Data(byte[] content) implements ControlState {
        public Data {
            Objects.requireNonNull(content, "content");
        }

        @Override
        public ControlType type() {
            return ControlType.DATA;
        }

        public String text() throws IOException {
            ByteBuffer bytes = ByteBuffer.wrap(content);
            if (bytes.hasRemaining() && bytes.get(bytes.limit() - 1) == '\n') {
                bytes.limit(bytes.limit() - 1);
            }
            String line;
            try {
                line = StandardCharsets.UTF_8.newDecoder().decode(bytes).toString();
            } catch (CharacterCodingException error) {
                throw new IOException("Invalid UTF-8 in Git request", error);
            }
            for (int i = 0; i < line.length(); i++) {
                if (line.charAt(i) < 32 || line.charAt(i) == 127) {
                    throw new IOException("Control character in Git request line");
                }
            }
            return line;
        }
    }

    /** Payload-free pkt-line markers; reading one never consumes bytes belonging to the next packet. */
    enum Control implements ControlState {
        FLUSH(ControlType.FLUSH),
        DELIMITER(ControlType.DELIMITER),
        RESPONSE_END(ControlType.RESPONSE_END);

        private final ControlType type;

        Control(ControlType type) {
            this.type = type;
        }

        @Override
        public ControlType type() {
            return type;
        }
    }

    static ControlState readFrom(BufferedByteInput input) throws IOException {
        return readNextFrom(input).orElseThrow(() -> new EOFException("Expected a Git pkt-line"));
    }

    static Optional<ControlState> readNextFrom(BufferedByteInput input) throws IOException {
        int first;
        try {
            first = input.readUnsignedByte();
        } catch (EOFException end) {
            return Optional.empty();
        }
        byte[] remaining = input.readBytes(PKT_LINE_HEADER_SIZE - 1);
        int header = first;
        for (byte value : remaining) {
            header = (header << 8) | (value & 0xff);
        }
        int length = 0;
        for (int shift = 24; shift >= 0; shift -= 8) {
            int digit = HEX_VALUES[(header >>> shift) & 0xff];
            if (digit < 0) {
                throw malformed(new GitGeneralException(INVALID_HEX_HEADER));
            }
            length = (length << 4) | digit;
        }
        if (length == 3) {
            throw malformed(new GitGeneralException(RESERVED_LENGTH));
        }
        if (length > MAX_PKT_LINE_LENGTH) {
            throw malformed(new GitGeneralException(LENGTH_EXCEEDS_LIMIT));
        }
        return Optional.of(switch (length) {
            case 0 -> Control.FLUSH;
            case 1 -> Control.DELIMITER;
            case 2 -> Control.RESPONSE_END;
            default -> new Data(input.readBytes(length - PKT_LINE_HEADER_SIZE));
        });
    }

    private static GitPktLineFormatException malformed(GitGeneralException cause) {
        return new GitPktLineFormatException("Invalid Git pkt-line header: " + cause.getMessage(), cause);
    }
}
