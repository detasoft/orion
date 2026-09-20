package pro.deta.orion.git.parser.wire;

import pro.deta.orion.git.parser.v2.proto.GitProtocolContext;
import pro.deta.orion.git.parser.v2.data.GitProtocolVersion;
import pro.deta.orion.git.parser.v2.data.GitTransport;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import pro.deta.orion.git.parser.v2.capability.GitCapability;
import pro.deta.orion.git.parser.v2.capability.GitCapabilityValue;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.git.parser.v2.pkt.SideBand;
import pro.deta.orion.git.parser.wire.advertisement.GitAdvertisedRef;
import pro.deta.orion.git.parser.wire.advertisement.GitLsRefsResponse;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.git.parser.wire.serialization.AsciiPacketSequenceSerialization;
import pro.deta.orion.git.parser.wire.serialization.OutputSerialization;
import pro.deta.orion.git.parser.wire.serialization.PacketListSerialization;
import pro.deta.orion.git.parser.wire.serialization.PktLineSerialization;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.BufferedByteOutput;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static pro.deta.orion.git.parser.v2.pkt.GitPktLine.MAX_PKT_LINE_LENGTH;
import static pro.deta.orion.git.parser.v2.pkt.GitPktLine.PKT_LINE_HEADER_SIZE;
import static pro.deta.orion.git.parser.wire.serialization.AsciiPacketUtils.*;

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

    public ByteBuf payloadBuffer(GitPktLine control) {
        Objects.requireNonNull(control, "control");
        return control instanceof GitPktLine.Data data ? Unpooled.wrappedBuffer(data.content()) : Unpooled.EMPTY_BUFFER;
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

    public void writeData(ByteBuf payload) throws IOException {
        Objects.requireNonNull(payload, "payload");
        int payloadLength = payload.readableBytes();
        if (payloadLength > MAX_PKT_LINE_LENGTH - PKT_LINE_HEADER_SIZE) {
            throw new IllegalArgumentException("Pkt-line payload exceeds Git pkt-line limit");
        }
        byte[] bytes = new byte[payloadLength];
        payload.getBytes(payload.readerIndex(), bytes);
        writeData(bytes);
    }

    public void writeText(String payload) throws IOException {
        writeData(utf8(payload));
    }

    public void writeTextLine(String payload) throws IOException {
        byte[] text = utf8(payload);
        byte[] line = new byte[text.length + 1];
        System.arraycopy(text, 0, line, 0, text.length);
        line[line.length - 1] = '\n';
        writeData(line);
    }

    public void writeFlush() throws IOException {
        GitPktLine.Control.FLUSH.writeTo(outputSink);
    }

    public void writeDelimiter() throws IOException {
        GitPktLine.Control.DELIMITER.writeTo(outputSink);
    }

    public void writeResponseEnd() throws IOException {
        GitPktLine.Control.RESPONSE_END.writeTo(outputSink);
    }

    public void writeSideBandData(ByteBuf payload) throws IOException {
        writeSideBand(SideBand.DATA, payload);
    }

    public void writeSideBandProgress(ByteBuf payload) throws IOException {
        writeSideBand(SideBand.PROGRESS, payload);
    }

    public void writeSideBandProgress(String payload) throws IOException {
        byte[] bytes = utf8(payload);
        writeSideBand(SideBand.PROGRESS, bytes, 0, bytes.length);
    }

    public void writeSideBandError(ByteBuf payload) throws IOException {
        writeSideBand(SideBand.ERROR, payload);
    }

    public void writeSideBandError(String payload) throws IOException {
        byte[] bytes = utf8(payload);
        writeSideBand(SideBand.ERROR, bytes, 0, bytes.length);
    }

    public void flush() throws IOException {
        outputSink.flush();
    }

    public void sendAdvertisement(GitV1Advertisement advertisement) throws IOException {
        Objects.requireNonNull(advertisement, "advertisement");
        sendSerialization(new PacketListSerialization(encodePackets(advertisement)));
    }

    public void sendV2UploadPackAdvertisement() throws IOException {
        sendV2UploadPackAdvertisement(GitWireConfiguration.allSupported().protocolV2());
    }

    public void sendV2UploadPackAdvertisement(GitWireConfiguration.ProtocolV2 configuration) throws IOException {
        Objects.requireNonNull(configuration, "configuration");
        List<String> capabilities = new ArrayList<>();
        capabilities.add("version 2\n");
        if (configuration.lsRefs()) {
            capabilities.add(configuration.lsRefsUnborn() ? "ls-refs=unborn\n" : "ls-refs\n");
        }
        if (configuration.fetch()) {
            List<String> fetchOptions = new ArrayList<>();
            if (configuration.shallow()) {
                fetchOptions.add("shallow");
            }
            if (configuration.waitForDone()) {
                fetchOptions.add("wait-for-done");
            }
            if (configuration.filter()) {
                fetchOptions.add("filter");
            }
            if (configuration.refInWant()) {
                fetchOptions.add("ref-in-want");
            }
            if (configuration.sidebandAll()) {
                fetchOptions.add("sideband-all");
            }
            if (configuration.packfileUris()) {
                fetchOptions.add(GitCapability.PACKFILE_URIS.wireName());
            }
            capabilities.add(fetchOptions.isEmpty() ? "fetch\n" : "fetch=" + String.join(" ", fetchOptions) + "\n");
        }
        if (configuration.serverOption()) {
            capabilities.add("server-option\n");
        }
        sendSerialization(new AsciiPacketSequenceSerialization(capabilities));
    }

    public void sendLsRefs(GitLsRefsResponse response) throws IOException {
        Objects.requireNonNull(response, "response");
        List<String> payloads = new ArrayList<>();
        for (GitLsRefsResponse.Ref ref : response.refs()) {
            Objects.requireNonNull(ref, "ref");
            String payload;
            if (ref instanceof GitLsRefsResponse.DirectRef direct) {
                validateObjectId(direct.objectId());
                validateToken(direct.name(), "direct.name");
                Objects.requireNonNull(direct.symrefTarget(), "direct.symrefTarget");
                Objects.requireNonNull(direct.peeledObjectId(), "direct.peeledObjectId");
                if (direct.symrefTarget().isPresent()) {
                    validateToken(direct.symrefTarget().get(), "direct.symrefTarget");
                }
                if (direct.peeledObjectId().isPresent()) {
                    validateObjectId(direct.peeledObjectId().get());
                }
                StringBuilder row = new StringBuilder().append(direct.objectId()).append(' ').append(direct.name());
                if (direct.symrefTarget().isPresent()) {
                    row.append(" symref-target:").append(direct.symrefTarget().get());
                }
                if (direct.peeledObjectId().isPresent()) {
                    row.append(" peeled:").append(direct.peeledObjectId().get());
                }
                payload = row.append('\n').toString();
            } else {
                GitLsRefsResponse.UnbornRef unborn = (GitLsRefsResponse.UnbornRef) ref;
                validateToken(unborn.name(), "unborn.name");
                validateToken(unborn.symrefTarget(), "unborn.symrefTarget");
                payload = "unborn " + unborn.name() + " symref-target:" + unborn.symrefTarget() + "\n";
            }
            validateAsciiPacket(payload, 0);
            payloads.add(payload);
        }
        sendSerialization(new AsciiPacketSequenceSerialization(payloads));
    }

    public void sendError(String message) throws IOException {
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        String payload = "ERR " + message + "\n";
        validateAsciiPacket(payload, 0);
        sendSerialization(new PktLineSerialization(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static void validateObjectId(String objectId) {
        Objects.requireNonNull(objectId, "objectId");
        if (objectId.length() != 40) {
            throw new IllegalArgumentException("Git object ID must contain 40 hexadecimal digits");
        }
        for (int index = 0; index < objectId.length(); index++) {
            char value = objectId.charAt(index);
            boolean hexadecimal = value >= '0' && value <= '9' || value >= 'a' && value <= 'f' || value >= 'A' && value <= 'F';
            if (!hexadecimal) {
                throw new IllegalArgumentException("Git object ID must contain 40 hexadecimal digits");
            }
        }
    }

    private static void validateToken(String token, String fieldName) {
        Objects.requireNonNull(token, fieldName);
        if (token.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be empty");
        }
        for (int index = 0; index < token.length(); index++) {
            char value = token.charAt(index);
            if (value <= 0x20 || value >= 0x7f) {
                throw new IllegalArgumentException(fieldName + " must be a protocol-safe ASCII token");
            }
        }
    }

    private void sendSerialization(OutputSerialization operation) throws IOException {
        operation.writeTo(this);
    }

    private BufferedByteInputV2 requireInput() {
        if (input == null) {
            throw new IllegalStateException("input is not configured");
        }
        return input;
    }

    public void writeRaw(byte[] bytes) throws IOException {
        outputSink.write(bytes);
    }

    public void writePacket(GitPktLine packet) throws IOException {
        writePacket(packet, SideBand.NONE);
    }

    public void writePacket(GitPktLine packet, SideBand sideBand) throws IOException {
        packet.writeTo(outputSink, sideBand);
    }

    public void writeData(byte[] payload) throws IOException {
        writePacket(new GitPktLine.Data(payload));
    }

    private void writeSideBand(SideBand channel, ByteBuf payload) throws IOException {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(payload, "payload");
        int payloadOffset = payload.readerIndex();
        int remaining = payload.readableBytes();
        if (remaining == 0) {
            return;
        }
        do {
            int chunkLength = Math.min(remaining, (MAX_PKT_LINE_LENGTH - PKT_LINE_HEADER_SIZE - 1));
            byte[] content = new byte[chunkLength];
            payload.getBytes(payloadOffset, content);
            new GitPktLine.Data(content).writeTo(outputSink, channel);
            payloadOffset += chunkLength;
            remaining -= chunkLength;
        } while (remaining > 0);
    }

    private void writeSideBand(SideBand channel, byte[] payload, int offset, int length) throws IOException {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(payload, "payload");
        int payloadOffset = offset;
        int remaining = length;
        if (remaining == 0) {
            return;
        }
        do {
            int chunkLength = Math.min(remaining, (MAX_PKT_LINE_LENGTH - PKT_LINE_HEADER_SIZE - 1));
            byte[] content = payloadOffset == 0 && chunkLength == payload.length
                    ? payload : Arrays.copyOfRange(payload, payloadOffset, payloadOffset + chunkLength);
            new GitPktLine.Data(content).writeTo(outputSink, channel);
            payloadOffset += chunkLength;
            remaining -= chunkLength;
        } while (remaining > 0);
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
