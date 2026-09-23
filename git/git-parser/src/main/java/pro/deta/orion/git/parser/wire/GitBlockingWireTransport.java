package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.git.parser.v2.capability.GitCapabilityValue;
import pro.deta.orion.git.parser.v2.data.GitProtocolVersion;
import pro.deta.orion.git.parser.v2.data.GitTransport;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.git.parser.v2.proto.GitProtocolContext;
import pro.deta.orion.git.parser.wire.advertisement.GitAdvertisedRef;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.BufferedByteOutput;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static pro.deta.orion.git.parser.v2.pkt.GitPktLine.MAX_PKT_LINE_LENGTH;
import static pro.deta.orion.git.parser.v2.pkt.GitPktLine.PKT_LINE_HEADER_SIZE;

public final class GitBlockingWireTransport {
    public static final int BUFFER_CAPACITY = 64 * 1024;

    private final BufferedByteInputV2 input;
    private final BufferedByteOutput outputSink;

    public GitBlockingWireTransport(BufferedByteOutput outputSink) {
        this(null, outputSink);
    }

    public GitBlockingWireTransport(BufferedByteInputV2 input, BufferedByteOutput outputSink) {
        this.input = input;
        this.outputSink = Objects.requireNonNull(outputSink, "outputSink");
    }

    public GitProtocolContext protocolContext(
            GitProtocolVersion version,
            GitTransport transport) {
        return new GitProtocolContext(requireInput(), outputSink, version, transport);
    }

    public GitPktLine readPacket() throws IOException {
        return readNextPacket().orElseThrow(() -> new EOFException("Expected a Git pkt-line"));
    }

    public Optional<GitPktLine> readNextPacket() throws IOException {
        return GitPktLine.readNextFrom(requireInput());
    }

    public int readRawInto(ByteBuf target, int maxLength) throws IOException {
        Objects.requireNonNull(target, "target");
        if (maxLength < 0) {
            throw new IllegalArgumentException("maxLength must be non-negative");
        }
        if (maxLength == 0 || !target.isWritable()) {
            return 0;
        }
        ByteBuffer bytes = requireInput().buffer();
        if (bytes == null) {
            return 0;
        }
        int count = Math.min(Math.min(maxLength, target.writableBytes()), bytes.remaining());
        target.writeBytes(bytes.slice(bytes.position(), count));
        bytes.position(bytes.position() + count);
        return count;
    }

    public void writeTextLine(String payload) throws IOException {
        byte[] text = utf8(payload);
        byte[] line = new byte[text.length + 1];
        System.arraycopy(text, 0, line, 0, text.length);
        line[line.length - 1] = '\n';
        new GitPktLine.Data(line).writeTo(outputSink);
    }

    public void writeFlush() throws IOException {
        GitPktLine.Control.FLUSH.writeTo(outputSink);
    }

    public void writeResponseEnd() throws IOException {
        GitPktLine.Control.RESPONSE_END.writeTo(outputSink);
    }

    public void flush() throws IOException {
        outputSink.flush();
    }

    public void sendAdvertisement(GitV1Advertisement advertisement) throws IOException {
        Objects.requireNonNull(advertisement, "advertisement");
        for (GitPktLine packet : encodePackets(advertisement)) {
            packet.writeTo(outputSink);
        }
        outputSink.flush();
    }

    private BufferedByteInputV2 requireInput() {
        if (input == null) {
            throw new IllegalStateException("input is not configured");
        }
        return input;
    }

    private static byte[] utf8(String payload) {
        Objects.requireNonNull(payload, "payload");
        return payload.getBytes(StandardCharsets.UTF_8);
    }

    private static List<GitPktLine> encodePackets(GitV1Advertisement advertisement) {
        List<GitPktLine> packets = new ArrayList<>();
        for (byte[] line : encodeLines(advertisement)) {
            int packetLength = line.length + PKT_LINE_HEADER_SIZE;
            if (packetLength > MAX_PKT_LINE_LENGTH) {
                throw new IllegalArgumentException("Advertisement line exceeds Git wire-line limit");
            }
            packets.add(new GitPktLine.Data(line));
        }
        packets.add(GitPktLine.Control.FLUSH);
        return List.copyOf(packets);
    }

    private static List<byte[]> encodeLines(GitV1Advertisement advertisement) {
        List<byte[]> lines = new ArrayList<>();
        List<GitAdvertisedRef> refs = advertisement.refs();
        GitAdvertisedRef first = refs.getFirst();
        List<String> capabilityTokens = new ArrayList<>();
        for (GitCapabilityValue capability : advertisement.capabilities()) {
            capabilityTokens.add(capability.wireToken());
        }
        lines.add(encodeLine(first.objectId() + " " + first.name() + "\0" + String.join(" ", capabilityTokens)));
        addPeeled(lines, first);
        for (int index = 1; index < refs.size(); index++) {
            GitAdvertisedRef ref = refs.get(index);
            lines.add(encodeLine(ref.objectId() + " " + ref.name()));
            addPeeled(lines, ref);
        }
        return lines;
    }

    private static void addPeeled(List<byte[]> lines, GitAdvertisedRef ref) {
        ref.peeledObjectId().ifPresent(objectId -> lines.add(encodeLine(objectId + " " + ref.name() + "^{}")));
    }

    private static byte[] encodeLine(String value) {
        return (value + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
