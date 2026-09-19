package pro.deta.orion.git.parser.v2.proto;

import pro.deta.orion.git.parser.v2.capability.GitCapabilities;
import pro.deta.orion.git.parser.v2.capability.GitCapability;
import pro.deta.orion.git.parser.v2.data.GitProtocolVersion;
import pro.deta.orion.git.parser.v2.data.GitTransport;
import pro.deta.orion.git.parser.v2.fetch.NegotiationResponse;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.git.parser.v2.pkt.GitPktLineOutput;
import pro.deta.orion.git.parser.v2.pkt.SideBand;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.BufferedByteOutput;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class GitProtocolContext {
    private final BufferedByteInputV2 input;
    private final BufferedByteOutput output;
    private final GitProtocolVersion version;
    private final GitTransport transport;

    public GitProtocolContext(BufferedByteInputV2 input, BufferedByteOutput output,
                              GitProtocolVersion version, GitTransport transport) {
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        this.version = Objects.requireNonNull(version, "version");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public GitProtocolVersion version() {
        return version;
    }

    public GitTransport transport() {
        return transport;
    }

    public BufferedByteInputV2 input() {
        return input;
    }

    public Reader reader() {
        return new Reader();
    }

    public Writer writer() {
        return new Writer();
    }

    public final class Reader {
        private Reader() {}

        public GitPktLine readGitPktLine() throws IOException {
            return GitPktLine.readNextFrom(input).orElseThrow(() -> new EOFException("Expected a Git pkt-line"));
        }
    }

    public final class Writer {
        private Writer() {}

        public void writeRef(RefId name, Optional<ObjectId> id, Optional<RefId> symbolic,
                             Optional<ObjectId> peeled) throws IOException {
            if (id.isEmpty() && (symbolic.isEmpty() || peeled.isPresent())) {
                throw new IllegalArgumentException("Unborn ref requires a symbolic target and cannot be peeled");
            }
            StringBuilder line = new StringBuilder(id.map(ObjectId::toHex).orElse("unborn"))
                    .append(' ').append(name.value());
            if (symbolic.isPresent()) {
                line.append(" symref-target:").append(symbolic.get().value());
            }
            if (peeled.isPresent()) {
                line.append(" peeled:").append(peeled.get().toHex());
            }
            writeText(line.append('\n').toString(), SideBand.NONE);
        }

        public void endRefs() throws IOException {
            GitPktLine.Control.FLUSH.writeTo(output);
        }

        public BufferedByteOutput beginPack(GitCapabilities capabilities, Map<RefId, ObjectId> wantedRefs)
                throws IOException {
            if (version == GitProtocolVersion.V2) {
                SideBand sideBand = capabilities.has(GitCapability.SIDEBAND_ALL) ? SideBand.DATA : SideBand.NONE;
                if (!wantedRefs.isEmpty()) {
                    writeText("wanted-refs\n", sideBand);
                    for (Map.Entry<RefId, ObjectId> ref : wantedRefs.entrySet()) {
                        writeText(ref.getValue().toHex() + " " + ref.getKey().value() + "\n", sideBand);
                    }
                    GitPktLine.Control.DELIMITER.writeTo(output);
                }
                writeText("packfile\n", sideBand);
            }
            if (!usesSideBand(capabilities)) {
                return output;
            }
            int limit = version == GitProtocolVersion.V2 || capabilities.has(GitCapability.SIDE_BAND_64K)
                    ? GitPktLine.MAX_PKT_LINE_LENGTH : 1000;
            return new GitPktLineOutput(output, SideBand.DATA, limit);
        }

        public void endPack(GitCapabilities capabilities) throws IOException {
            if (usesSideBand(capabilities)) {
                GitPktLine.Control.FLUSH.writeTo(output);
            }
        }

        private boolean usesSideBand(GitCapabilities capabilities) {
            return version == GitProtocolVersion.V2 || capabilities.has(GitCapability.SIDE_BAND)
                    || capabilities.has(GitCapability.SIDE_BAND_64K);
        }

        private void writeText(String text, SideBand sideBand) throws IOException {
            new GitPktLine.Data(text.getBytes(StandardCharsets.UTF_8)).writeTo(output, sideBand);
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
