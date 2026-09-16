package pro.deta.orion.git.parser.v2;

import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.net.io.BufferedByteInput;

import java.io.IOException;
import java.io.EOFException;
import java.util.Objects;

/**
 * Reads complete Git pkt-lines from a borrowed BufferedByteInput through GitPktLine.readNextFrom.
 * readGitPktLine returns either Data with all payload bytes or a payload-free Control marker.
 * Commands obtain validated UTF-8 through Data.text when their grammar requires text; binary packets stay raw.
 * No stream is closed. Header/payload truncation is an IOException, never an implicit end of negotiation.
 */
public final class GitReader {
    private final BufferedByteInput input;

    public GitReader(BufferedByteInput input) {
        this.input = Objects.requireNonNull(input, "input");
    }

    public GitPktLine readGitPktLine() throws IOException {
        return GitPktLine.readNextFrom(input).orElseThrow(() -> new EOFException("Expected a Git pkt-line"));
    }
}
