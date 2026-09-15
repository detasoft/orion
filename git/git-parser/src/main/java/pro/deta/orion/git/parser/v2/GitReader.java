package pro.deta.orion.git.parser.v2;

import pro.deta.orion.git.parser.v2.data.FetchRequest;
import pro.deta.orion.git.parser.v2.data.NegotiationMessage;
import pro.deta.orion.git.parser.v2.fetch.FetchNegotiator;
import pro.deta.orion.net.io.BufferedByteInput;

import java.io.IOException;
import java.util.Objects;

/**
 * Reads Git wire requests and validates their syntax independently of repository and access-control logic.
 * Keeps pkt-line framing and payload parsing internal; reads negotiation messages individually so the server
 * can reply between reads. Pack ingestion belongs to PushCommand.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code readCommandHeader()} - read a protocol v2 command and its capabilities.</li>
 *   <li>{@code readLsRefsRequest()} - collect ls-refs arguments.</li>
 *   <li>{@code readFetchRequest()} - collect protocol v2 fetch arguments.</li>
 *   <li>{@code readLegacyUploadRequest()} - collect legacy wants and negotiated capabilities.</li>
 *   <li>{@code readNegotiationMessage()} - read one have, done, or flush message.</li>
 *   <li>{@code readPushRequest()} - read ref-update commands and capabilities before the pack body.</li>
 * </ul>
 * Constructor borrows input without consuming or closing it. readFetchRequest produces data.FetchRequest
 * without running negotiation or accessing storage. readNegotiationMessage implements the typed
 * negotiation input boundary; framing and decoding remain placeholders. Unexpected EOF is IOException.
 * Protocol-specific setup must distinguish the want-section flush before exposing negotiation messages.
 * Other method names and signatures are provisional; values remain independent of native storage.
 */
public final class GitReader implements FetchNegotiator.Input {
    private final BufferedByteInput input;

    public GitReader(BufferedByteInput input) {
        this.input = Objects.requireNonNull(input, "input");
    }

    public FetchRequest readFetchRequest() throws IOException {
        throw new UnsupportedOperationException("Fetch request decoding is not implemented");
    }

    @Override
    public NegotiationMessage readNegotiationMessage() throws IOException {
        throw new UnsupportedOperationException("Negotiation message decoding is not implemented");
    }
}
