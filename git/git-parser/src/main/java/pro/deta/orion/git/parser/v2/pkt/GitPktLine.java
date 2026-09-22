package pro.deta.orion.git.parser.v2.pkt;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.git.parser.wire.GitPktLineFormatException;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.BufferedByteOutput;

import java.io.IOException;
import java.io.EOFException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

import static pro.deta.orion.git.parser.wire.GitNativeUtils.HEX_VALUES;
import static pro.deta.orion.git.parser.wire.GitNativeUtils.hexDigit;

/**
 * One completely read pkt-line: Data owns its payload, while Control has no payload.
 * readNextFrom validates the header and reads exactly the declared payload through BufferedByteInputV2.readBytes.
 * DATA stays binary until text() is explicitly requested; arbitrary pack bytes need not be valid UTF-8.
 * Lengths are derived from the variant, never independently mutable header state.
 * No stream or reference-counted buffer is retained. EOF during the header or payload is an IOException.
 * readNextFrom permits clean EOF before a packet; partial headers and payloads always fail.
 * writeTo validates the packet size before writing its header and payload; flushing belongs to the caller.
 * Its optional SideBand argument prefixes Data with a channel byte and includes it in the wire length.
 * Control markers remain unprefixed. The default is NONE; payload bytes never include an implicit channel.
 */
public sealed interface GitPktLine permits GitPktLine.Control, GitPktLine.Data {
    int PKT_LINE_HEADER_SIZE = 4;
    int MAX_PKT_LINE_LENGTH = 65_520;

    default int payloadLength() {
        return this instanceof Data data ? data.content().length : 0;
    }

    default int length() {
        return PKT_LINE_HEADER_SIZE + payloadLength();
    }

    default void writeTo(BufferedByteOutput output) throws IOException {
        writeTo(output, SideBand.NONE);
    }

    default void writeTo(BufferedByteOutput output, SideBand sideBand) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(sideBand, "sideBand");
        int wireLength = switch (this) {
            case Data data -> data.length() + (sideBand == SideBand.NONE ? 0 : 1);
            case Control control -> control.wireValue();
        };
        writeHeader(output, wireLength, this instanceof Data ? sideBand : SideBand.NONE);
        if (this instanceof Data data) {
            output.write(data.content());
        }
    }

    static void writeDataTo(BufferedByteOutput output, ByteBuf content, SideBand sideBand) throws IOException {
        int length = PKT_LINE_HEADER_SIZE + content.readableBytes() + (sideBand == SideBand.NONE ? 0 : 1);
        writeHeader(output, length, sideBand);
        output.write(content);
    }

    private static void writeHeader(BufferedByteOutput output, int wireLength, SideBand sideBand)
            throws IOException {
        if (wireLength < 0 || wireLength > MAX_PKT_LINE_LENGTH) {
            throw new IllegalArgumentException("Pkt-line payload exceeds Git pkt-line limit");
        }
        boolean hasChannel = sideBand != SideBand.NONE;
        byte[] header = new byte[PKT_LINE_HEADER_SIZE + (hasChannel ? 1 : 0)];
        for (int index = 0; index < PKT_LINE_HEADER_SIZE; index++) {
            int shift = (PKT_LINE_HEADER_SIZE - 1 - index) * 4;
            header[index] = hexDigit((wireLength >>> shift) & 0x0f);
        }
        if (hasChannel) {
            header[PKT_LINE_HEADER_SIZE] = sideBand.wireValue();
        }
        output.write(header);
    }


    /**
     * Raw payload owned by this packet. content exposes the bytes without another copy.
     * text decodes strict UTF-8, removes one final LF, and rejects embedded ASCII control characters.
     * Empty data remains distinct from FLUSH. Binary consumers use content and do not call text.
     */
    record Data(byte[] content) implements GitPktLine {
        public Data {
            Objects.requireNonNull(content, "content");
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
    enum Control implements GitPktLine {
        FLUSH(0), DELIMITER(1), RESPONSE_END(2);

        private final int wireValue;

        Control(int wireValue) {
            this.wireValue = wireValue;
        }

        public int wireValue() {
            return wireValue;
        }

        public static Control valueOf(int wireValue) throws GitPktLineFormatException {
            for (Control control : values()) {
                if (control.wireValue == wireValue) {
                    return control;
                }
            }
            throw malformed("Pkt-line length 0003 is reserved");
        }
    }

    static Optional<GitPktLine> readNextFrom(BufferedByteInputV2 input) throws IOException {
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
                throw malformed("Pkt-line length contains non-hex byte");
            }
            length = (length << 4) | digit;
        }
        return Optional.of(valueOf(length, input));
    }

    private static GitPktLine valueOf(int length, BufferedByteInputV2 input) throws IOException {
        if (length > MAX_PKT_LINE_LENGTH) {
            throw malformed("Pkt-line length exceeds Git pkt-line limit");
        }
        if (length < PKT_LINE_HEADER_SIZE) {
            return Control.valueOf(length);
        }
        return new Data(input.readBytes(length - PKT_LINE_HEADER_SIZE));
    }

    private static GitPktLineFormatException malformed(String reason) {
        return new GitPktLineFormatException("Invalid Git pkt-line header: " + reason);
    }
}
