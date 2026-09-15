package pro.deta.orion.git.parser.v2;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import pro.deta.orion.git.parser.wire.control.ControlState;
import pro.deta.orion.net.io.BufferedByteInput;

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
 * FetchNegotiator owns initial argument and subsequent have/done/flush parsing.
 * EOF and malformed input are IOException, never implicit DONE.
 * Other command parsing remains to be added; no second fetch request parser is retained here.
 * readText bulk-reads one payload and releases its temporary buffer even when decoding fails.
 * A trailing LF is excluded before decoding, avoiding a second String for the stripped line.
 */
public final class GitReader {
    private final BufferedByteInput input;

    public GitReader(BufferedByteInput input) {
        this.input = Objects.requireNonNull(input, "input");
    }

    public ControlState readControlState() throws IOException {
        return ControlState.readFrom(input);
    }

    public String readText(ControlState packet) throws IOException {
        Objects.requireNonNull(packet, "packet");
        if (packet.type() != ControlState.ControlType.DATA) {
            throw new IOException("Expected a data packet");
        }
        ByteBuf payload = input.readCopy(packet.payloadLength(), UnpooledByteBufAllocator.DEFAULT);
        try {
            return decodeLine(payload.nioBuffer());
        } finally {
            payload.release();
        }
    }

    private static String decodeLine(ByteBuffer bytes) throws IOException {
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
