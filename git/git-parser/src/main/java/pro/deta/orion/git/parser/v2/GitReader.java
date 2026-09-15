package pro.deta.orion.git.parser.v2;

import pro.deta.orion.git.parser.v2.fetch.NegotiationMessage;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.wire.GitPktLineFormatException;
import pro.deta.orion.git.parser.wire.control.ControlState;
import pro.deta.orion.net.io.BufferedByteInput;
import pro.deta.orion.util.Result;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Reads Git packet framing and decoded text from a borrowed BufferedByteInput. Uses the existing
 * ControlState decoder for header validation and limits. Reads exactly the current packet; later packets
 * remain available through the same input. No stream is closed and no repository operation is performed.
 * UTF-8 text permits one trailing LF, rejects embedded line/control characters, and fails on malformed UTF-8.
 * FetchNegotiator's version-specific parsers consume initial arguments. readNegotiationMessage handles
 * subsequent legacy have/done/flush packets. EOF and malformed input are IOException, never implicit DONE.
 * Other command parsing remains to be added; no second fetch request parser is retained here.
 */
public final class GitReader {
    private final BufferedByteInput input;

    public GitReader(BufferedByteInput input) {
        this.input = Objects.requireNonNull(input, "input");
    }

    public ControlState readControlState() throws IOException {
        int header = 0;
        for (int i = 0; i < ControlState.PKT_LINE_HEADER_SIZE; i++) {
            header = (header << 8) | input.readUnsignedByte();
        }
        Result<ControlState> result = ControlState.readControlType(header);
        if (result instanceof Result.Success<ControlState> success) {
            return success.value();
        }
        Result.Failure<ControlState> failure = (Result.Failure<ControlState>) result;
        throw new GitPktLineFormatException("Invalid pkt-line header: " + failure.getMessage(),
                failure.throwable());
    }

    public String readText(ControlState packet) throws IOException {
        Objects.requireNonNull(packet, "packet");
        if (packet.type() != ControlState.ControlType.DATA) {
            throw new IOException("Expected a data packet");
        }
        byte[] bytes = new byte[packet.payloadLength()];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) input.readUnsignedByte();
        }
        String line;
        try {
            line = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw new IOException("Invalid UTF-8 in Git request", error);
        }
        if (line.endsWith("\n")) {
            line = line.substring(0, line.length() - 1);
        }
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) < 32 || line.charAt(i) == 127) {
                throw new IOException("Control character in Git request line");
            }
        }
        return line;
    }

    public NegotiationMessage readNegotiationMessage() throws IOException {
        ControlState packet = readControlState();
        if (packet.type() == ControlState.ControlType.FLUSH) {
            return NegotiationMessage.Control.END_ROUND;
        }
        String line = readText(packet);
        if (line.equals("done")) {
            return NegotiationMessage.Control.DONE;
        }
        if (line.startsWith("have ")) {
            try {
                return new NegotiationMessage.Have(new ObjectId(line.substring(5)));
            } catch (IllegalArgumentException error) {
                throw new IOException("Invalid have object ID", error);
            }
        }
        throw new IOException("Unexpected negotiation message: " + line);
    }
}
