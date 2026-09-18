package pro.deta.orion.git.parser.v2.proto;

import pro.deta.orion.git.parser.v2.GitWriter;
import pro.deta.orion.git.parser.v2.data.GitProtocolVersion;
import pro.deta.orion.git.parser.v2.data.GitTransport;
import pro.deta.orion.git.parser.v2.fetch.NegotiationResponse;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.git.parser.v2.pkt.SideBand;
import pro.deta.orion.net.io.BufferedByteInput;
import pro.deta.orion.net.io.BufferedByteOutput;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

public class GitProtocolContext {
    private final BufferedByteInput input;
    private final BufferedByteOutput output;
    private final GitProtocolVersion version;
    private final GitTransport transport;

    public GitProtocolContext(BufferedByteInput input, BufferedByteOutput output, GitProtocolVersion version, GitTransport transport) {
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        this.version = Objects.requireNonNull(version, "version");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public class Reader {
        public GitPktLine readGitPktLine() throws IOException {
            return GitPktLine.readNextFrom(input).orElseThrow(() -> new EOFException("Expected a Git pkt-line"));
        }
    }

    class Writer {
        private void writeText(String text, SideBand sideBand) throws IOException {
            new GitPktLine.Data(text.getBytes(StandardCharsets.US_ASCII)).writeTo(output, sideBand);
        }

        public void flush() throws IOException {
            output.flush();
        }
        public void writeNegotiationRound(List<NegotiationResponse> responses, SideBand sideBand) throws IOException {
            Objects.requireNonNull(responses, "responses");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(sideBand, "sideBand");
            if (responses.isEmpty()) {
                return;
            }
            if (version == GitProtocolVersion.V2) {
                writeText("acknowledgments\n", sideBand);
            }
            for (NegotiationResponse response : responses) {
                String text = switch (response) {
                    case NegotiationResponse.Ack ack -> "ACK " + ack.objectId().toHex() + switch (ack.status()) {
                        case PLAIN -> "\n";
                        case CONTINUE -> " continue\n";
                        case COMMON -> " common\n";
                        case READY -> " ready\n";
                    };
                    case NegotiationResponse.Control.NAK -> "NAK\n";
                    case NegotiationResponse.Control.READY -> "ready\n";
                };
                writeText(text, sideBand);
            }
            if (version == GitProtocolVersion.V2) {
                GitPktLine.Control end = responses.contains(NegotiationResponse.Control.READY)
                        ? GitPktLine.Control.DELIMITER : GitPktLine.Control.FLUSH;
                end.writeTo(output, sideBand);
            }
        }


    }
}
