package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;
import pro.deta.orion.git.nativestorage.upload.NativePackfileUri;
import pro.deta.orion.git.parser.wire.advertisement.GitAdvertisedRef;
import pro.deta.orion.git.parser.wire.advertisement.GitLsRefsResponse;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.git.parser.v2.capability.GitCapability;
import pro.deta.orion.git.parser.v2.capability.GitCapabilityValue;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.git.parser.v2.pkt.SideBand;
import pro.deta.orion.git.parser.wire.serialization.AsciiPacketSequenceSerialization;
import pro.deta.orion.git.parser.wire.serialization.OutputSerialization;
import pro.deta.orion.git.parser.wire.serialization.PacketListSerialization;
import pro.deta.orion.git.parser.wire.serialization.PktLineSerialization;
import pro.deta.orion.net.io.BufferedByteInput;
import pro.deta.orion.net.io.BufferedByteOutput;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.EOFException;
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

    private final BufferedByteInput input;
    private final BufferedByteOutput outputSink;

    public GitBlockingWireTransport(BufferedByteOutput outputSink) {
        this(null, outputSink);
    }

    public GitBlockingWireTransport(BufferedByteInput input, BufferedByteOutput outputSink) {
        this.input = input;
        this.outputSink = Objects.requireNonNull(outputSink, "outputSink");
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
        return requireInput().readInto(target, maxLength);
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

    public void sendProtocolV2FetchAcknowledgments(List<GitObjectId> acknowledgments, boolean sidebandAll) throws IOException {
        Objects.requireNonNull(acknowledgments, "acknowledgments");
        List<String> payloads = new ArrayList<>();
        payloads.add("acknowledgments\n");
        if (acknowledgments.isEmpty()) {
            payloads.add("NAK\n");
        } else {
            for (GitObjectId acknowledgment : acknowledgments) {
                Objects.requireNonNull(acknowledgment, "acknowledgment");
                validateObjectId(acknowledgment.value());
                payloads.add("ACK " + acknowledgment.value() + "\n");
            }
        }
        if (sidebandAll) {
            sendSerialization(new PacketListSerialization(encodeAsciiPackets(payloads, true), SideBand.DATA));
            return;
        }
        sendSerialization(new AsciiPacketSequenceSerialization(payloads));
    }

    public void sendNak() throws IOException {
        sendPktLine(List.of("NAK\n"), "Failed to serialize legacy upload-pack NAK");
    }

    public void sendLegacyShallowInfo(
            Set<GitObjectId> shallowBoundaries,
            Set<GitObjectId> unshallowBoundaries) throws IOException {
        Objects.requireNonNull(shallowBoundaries, "shallowBoundaries");
        Objects.requireNonNull(unshallowBoundaries, "unshallowBoundaries");
        List<String> payloads = new ArrayList<>();
        for (GitObjectId shallowBoundary : shallowBoundaries) {
            validateObjectId(shallowBoundary.value());
            payloads.add("shallow " + shallowBoundary.value() + "\n");
        }
        for (GitObjectId unshallowBoundary : unshallowBoundaries) {
            validateObjectId(unshallowBoundary.value());
            payloads.add("unshallow " + unshallowBoundary.value() + "\n");
        }
        sendSerialization(new AsciiPacketSequenceSerialization(payloads));
    }

    public void sendAck(GitObjectId objectId, AckStatus status) throws IOException {
        Objects.requireNonNull(objectId, "objectId");
        Objects.requireNonNull(status, "status");
        sendPktLine(List.of("ACK ", objectId.value(), status.wireSuffix, "\n"), "Failed to serialize legacy upload-pack ACK");
    }

    public void sendLegacyReceivePackStatus(List<ReceiveCommandStatus> statuses, boolean sideBand64k) throws IOException {
        sendLegacyReceivePackStatus("ok", statuses, sideBand64k);
    }

    public void sendLegacyReceivePackStatus(
            String unpackStatus,
            List<ReceiveCommandStatus> statuses,
            boolean sideBand64k) throws IOException {
        Optional<String> unpackStatusFailure =
                statusMessageValidationFailure(unpackStatus);
        if (unpackStatusFailure.isPresent()) {
            throw new IllegalArgumentException(
                    "unpackStatus " + unpackStatusFailure.get());
        }
        if (statuses == null) {
            throw new IllegalArgumentException("statuses must not be null");
        }
        for (ReceiveCommandStatus status : statuses) {
            Optional<String> validationFailure = receiveCommandStatusValidationFailure(status);
            if (validationFailure.isPresent()) {
                throw new IllegalArgumentException(validationFailure.get());
            }
        }
        sendSerialization(new ReceivePackStatusSerialization(
                unpackStatus,
                List.copyOf(statuses),
                sideBand64k));
    }

    public LegacySideBandResponse beginLegacySideBand64k(NativePackProducer producer, boolean sendNakBeforePack) {
        Objects.requireNonNull(producer, "producer");
        return new LegacySideBandResponse(producer, sendNakBeforePack);
    }

    public LegacyPackResponse beginLegacyPack(NativePackProducer producer, boolean sendNakBeforePack) {
        Objects.requireNonNull(producer, "producer");
        return new LegacyPackResponse(producer, sendNakBeforePack);
    }

    public ProtocolV2PackfileResponse beginProtocolV2Packfile(
            NativePackProducer producer) {
        return beginProtocolV2Packfile(producer, Set.of());
    }

    public ProtocolV2PackfileResponse beginProtocolV2Packfile(
            NativePackProducer producer,
            Set<GitObjectId> shallowBoundaries) {
        return beginProtocolV2Packfile(producer, shallowBoundaries, Map.of());
    }

    public ProtocolV2PackfileResponse beginProtocolV2Packfile(
            NativePackProducer producer,
            Set<GitObjectId> shallowBoundaries,
            Map<String, GitObjectId> wantedRefs) {
        return beginProtocolV2Packfile(
                producer,
                shallowBoundaries,
                Set.of(),
                wantedRefs,
                List.of(),
                false);
    }

    public ProtocolV2PackfileResponse beginProtocolV2Packfile(
            NativePackProducer producer,
            Set<GitObjectId> shallowBoundaries,
            Map<String, GitObjectId> wantedRefs,
            List<NativePackfileUri> packfileUris) {
        return beginProtocolV2Packfile(
                producer,
                shallowBoundaries,
                Set.of(),
                wantedRefs,
                packfileUris,
                false);
    }

    public ProtocolV2PackfileResponse beginProtocolV2Packfile(
            NativePackProducer producer,
            Set<GitObjectId> shallowBoundaries,
            Map<String, GitObjectId> wantedRefs,
            List<NativePackfileUri> packfileUris,
            boolean sidebandAll) {
        return beginProtocolV2Packfile(
                producer,
                shallowBoundaries,
                Set.of(),
                wantedRefs,
                packfileUris,
                sidebandAll);
    }

    public ProtocolV2PackfileResponse beginProtocolV2Packfile(
            NativePackProducer producer,
            Set<GitObjectId> shallowBoundaries,
            Set<GitObjectId> unshallowBoundaries,
            Map<String, GitObjectId> wantedRefs,
            List<NativePackfileUri> packfileUris,
            boolean sidebandAll) {
        Objects.requireNonNull(shallowBoundaries, "shallowBoundaries");
        Objects.requireNonNull(unshallowBoundaries, "unshallowBoundaries");
        Objects.requireNonNull(wantedRefs, "wantedRefs");
        Objects.requireNonNull(packfileUris, "packfileUris");
        Objects.requireNonNull(producer, "producer");
        return new ProtocolV2PackfileResponse(
                producer,
                shallowBoundaries,
                unshallowBoundaries,
                wantedRefs,
                packfileUris,
                sidebandAll);
    }

    private void sendPktLine(List<String> payloadParts, String failureMessage) throws IOException {
        String payload = String.join("", payloadParts);
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        int packetLength = payloadBytes.length + PKT_LINE_HEADER_SIZE;
        if (packetLength > MAX_PKT_LINE_LENGTH) {
            throw new IllegalArgumentException(failureMessage, new IllegalArgumentException("Git wire-line exceeds maximum length"));
        }
        sendSerialization(new PktLineSerialization(payloadBytes));
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

    private static Optional<String> receiveCommandStatusValidationFailure(ReceiveCommandStatus status) {
        if (status == null) {
            return Optional.of("status must not be null");
        }
        Optional<String> refNameFailure = tokenValidationFailure(status.refName(), "status.refName");
        if (refNameFailure.isPresent()) {
            return refNameFailure;
        }
        Optional<String> messageFailure = status.ok() ? Optional.empty() : statusMessageValidationFailure(status.message());
        if (messageFailure.isPresent()) {
            return messageFailure;
        }
        int payloadLength = receiveCommandStatusPayload(status).getBytes(StandardCharsets.UTF_8).length;
        if (payloadLength + PKT_LINE_HEADER_SIZE > MAX_PKT_LINE_LENGTH) {
            return Optional.of("Legacy receive-pack status exceeds maximum length");
        }
        return Optional.empty();
    }

    private static Optional<String> tokenValidationFailure(String token, String fieldName) {
        if (token == null) {
            return Optional.of(fieldName + " must not be null");
        }
        if (token.isEmpty()) {
            return Optional.of(fieldName + " must not be empty");
        }
        for (int index = 0; index < token.length(); index++) {
            char value = token.charAt(index);
            if (value <= 0x20 || value == 0x7f) {
                return Optional.of(fieldName + " must not contain control characters or spaces");
            }
        }
        return Optional.empty();
    }

    private static Optional<String> statusMessageValidationFailure(String message) {
        if (message == null) {
            return Optional.of("status.message must not be null");
        }
        if (message.isBlank()) {
            return Optional.of("status.message must not be blank");
        }
        for (int index = 0; index < message.length(); index++) {
            char value = message.charAt(index);
            if (value <= 0x20 || value >= 0x7f) {
                return Optional.of("status.message must contain printable non-space ASCII");
            }
        }
        return Optional.empty();
    }

    private void sendSerialization(OutputSerialization operation) throws IOException {
        operation.writeTo(this);
    }

    private BufferedByteInput requireInput() {
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
            int chunkLength = Math.min(remaining, SideBandOutput.MAXIMUM_PAYLOAD);
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
            int chunkLength = Math.min(remaining, SideBandOutput.MAXIMUM_PAYLOAD);
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

    private final class RawPackOutput implements BufferedByteOutput {
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return;
            }
            outputSink.write(bytes, offset, length);
        }

        @Override
        public void write(ByteBuf buffer) throws IOException {
            int length = buffer.readableBytes();
            if (length == 0) {
                return;
            }
            outputSink.write(buffer);
        }

        @Override
        public void flush() throws IOException {
            outputSink.flush();
        }
    }

    private final class SideBandOutput implements BufferedByteOutput {
        private static final int MAXIMUM_PAYLOAD = MAX_PKT_LINE_LENGTH - PKT_LINE_HEADER_SIZE - 1;

        private final SideBand channel;

        private SideBandOutput(SideBand channel) {
            this.channel = Objects.requireNonNull(channel, "channel");
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return;
            }
            writeSideBand(channel, bytes, offset, length);
        }

        @Override
        public void write(ByteBuf buffer) throws IOException {
            int length = buffer.readableBytes();
            if (length == 0) {
                return;
            }
            writeSideBand(channel, buffer);
        }

        @Override
        public void flush() throws IOException {
            outputSink.flush();
        }
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


    public enum AckStatus {
        FINAL(""), CONTINUE(" continue"), COMMON(" common"), READY(" ready");

        private final String wireSuffix;

        AckStatus(String wireSuffix) {
            this.wireSuffix = wireSuffix;
        }
    }

    public record ReceiveCommandStatus(String refName, boolean ok, String message) {
        public ReceiveCommandStatus {
            Objects.requireNonNull(refName, "refName");
            Objects.requireNonNull(message, "message");
        }
    }

    public final class LegacySideBandResponse implements AutoCloseable {

        private final NativePackProducer producer;
        private final boolean sendNakBeforePack;
        private boolean closed;

        private LegacySideBandResponse(NativePackProducer producer, boolean sendNakBeforePack) {
            this.producer = producer;
            this.sendNakBeforePack = sendNakBeforePack;
        }

        public void advance() throws IOException {
            if (closed) {
                throw new IllegalStateException("Legacy side-band response is closed");
            }
            try {
                if (sendNakBeforePack) {
                    writeText("NAK\n");
                }
                SideBandOutput packOutput = new SideBandOutput(SideBand.DATA);
                producer.writeTo(packOutput);
                writeFlush();
                flush();
                complete();
            } catch (IOException | RuntimeException error) {
                closeAfterFailure(error);
                throw error;
            }
        }

        private void closeAfterFailure(Throwable failure) {
            try {
                close();
            } catch (Throwable closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (producer != null) {
                producer.close();
            }
        }

        private void complete() {
            close();
        }
    }

    public final class LegacyPackResponse implements AutoCloseable {

        private final NativePackProducer producer;
        private final boolean sendNakBeforePack;
        private boolean closed;

        private LegacyPackResponse(NativePackProducer producer, boolean sendNakBeforePack) {
            this.producer = producer;
            this.sendNakBeforePack = sendNakBeforePack;
        }

        public void advance() throws IOException {
            if (closed) {
                throw new IllegalStateException("Legacy pack response is closed");
            }
            try {
                if (sendNakBeforePack) {
                    writeText("NAK\n");
                }
                RawPackOutput packOutput = new RawPackOutput();
                producer.writeTo(packOutput);
                flush();
                close();
            } catch (IOException | RuntimeException error) {
                closeAfterFailure(error);
                throw error;
            }
        }

        private void closeAfterFailure(Throwable failure) {
            try {
                close();
            } catch (Throwable closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (producer != null) {
                producer.close();
            }
        }
    }

    public final class ProtocolV2PackfileResponse implements AutoCloseable {
        private final NativePackProducer producer;
        private final List<GitPktLine> prePackSectionPackets;
        private final GitPktLine packfileHeader;
        private final SideBand sideBand;
        private boolean closed;

        private ProtocolV2PackfileResponse(
                NativePackProducer producer,
                Set<GitObjectId> shallowBoundaries,
                Set<GitObjectId> unshallowBoundaries,
                Map<String, GitObjectId> wantedRefs,
                List<NativePackfileUri> packfileUris,
                boolean sidebandAll) {
            this.producer = producer;
            this.prePackSectionPackets = prePackSectionPackets(
                    shallowBoundaries,
                    unshallowBoundaries,
                    wantedRefs,
                    packfileUris,
                    sidebandAll);
            this.packfileHeader = encodeAsciiPacket("packfile\n", sidebandAll);
            this.sideBand = sidebandAll ? SideBand.DATA : SideBand.NONE;
        }

        public void advance() throws IOException {
            if (closed) {
                throw new IllegalStateException("Protocol v2 packfile response is closed");
            }
            try {
                for (GitPktLine packet : prePackSectionPackets) {
                    writePacket(packet, sideBand);
                }
                writePacket(packfileHeader, sideBand);
                producer.writeTo(new SideBandOutput(SideBand.DATA));
                writeFlush();
                flush();
                close();
            } catch (IOException | RuntimeException error) {
                closeAfterFailure(error);
                throw error;
            }
        }

        private void closeAfterFailure(Throwable failure) {
            try {
                close();
            } catch (Throwable closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (producer != null) {
                producer.close();
            }
        }

        private static List<GitPktLine> prePackSectionPackets(
                Set<GitObjectId> shallowBoundaries,
                Set<GitObjectId> unshallowBoundaries,
                Map<String, GitObjectId> wantedRefs,
                List<NativePackfileUri> packfileUris,
                boolean sidebandAll) {
            Objects.requireNonNull(shallowBoundaries, "shallowBoundaries");
            Objects.requireNonNull(unshallowBoundaries, "unshallowBoundaries");
            Objects.requireNonNull(wantedRefs, "wantedRefs");
            Objects.requireNonNull(packfileUris, "packfileUris");
            if (shallowBoundaries.isEmpty()
                    && unshallowBoundaries.isEmpty()
                    && wantedRefs.isEmpty()
                    && packfileUris.isEmpty()) {
                return List.of();
            }
            List<GitPktLine> packets = new ArrayList<>();
            if (!shallowBoundaries.isEmpty()
                    || !unshallowBoundaries.isEmpty()) {
                packets.add(encodeAsciiPacket("shallow-info\n", sidebandAll));
                for (GitObjectId shallowBoundary : shallowBoundaries) {
                    Objects.requireNonNull(shallowBoundary, "shallowBoundary");
                    validateObjectId(shallowBoundary.value());
                    packets.add(encodeAsciiPacket("shallow " + shallowBoundary.value() + "\n", sidebandAll));
                }
                for (GitObjectId unshallowBoundary : unshallowBoundaries) {
                    Objects.requireNonNull(
                            unshallowBoundary,
                            "unshallowBoundary");
                    validateObjectId(unshallowBoundary.value());
                    packets.add(encodeAsciiPacket(
                            "unshallow "
                                    + unshallowBoundary.value()
                                    + "\n",
                            sidebandAll));
                }
                packets.add(GitPktLine.Control.DELIMITER);
            }
            if (!wantedRefs.isEmpty()) {
                packets.add(encodeAsciiPacket("wanted-refs\n", sidebandAll));
                for (Map.Entry<String, GitObjectId> wantedRef : new LinkedHashMap<>(wantedRefs).entrySet()) {
                    String refName = validateRefName(wantedRef.getKey(), "wantedRef.name");
                    GitObjectId objectId = Objects.requireNonNull(wantedRef.getValue(), "wantedRef.objectId");
                    validateObjectId(objectId.value());
                    packets.add(encodeAsciiPacket(objectId.value() + " " + refName + "\n", sidebandAll));
                }
                packets.add(GitPktLine.Control.DELIMITER);
            }
            if (!packfileUris.isEmpty()) {
                packets.add(encodeAsciiPacket(GitCapability.PACKFILE_URIS.wireName() + "\n", sidebandAll));
                for (NativePackfileUri packfileUri : packfileUris) {
                    Objects.requireNonNull(packfileUri, "packfileUri");
                    packets.add(encodeAsciiPacket(packfileUri.packHash() + " " + packfileUri.uri() + "\n", sidebandAll));
                }
                packets.add(GitPktLine.Control.DELIMITER);
            }
            return List.copyOf(packets);
        }

        private static String validateRefName(String refName, String fieldName) {
            Objects.requireNonNull(refName, fieldName);
            if (!isValidWantedRefName(refName)) {
                throw new IllegalArgumentException(fieldName + " must be HEAD or a full Git ref name");
            }
            return refName;
        }

        private static boolean isValidWantedRefName(String refName) {
            return "HEAD".equals(refName) || isValidFullRefName(refName);
        }

        private static boolean isValidFullRefName(String refName) {
            if (!refName.startsWith("refs/") || refName.length() == "refs/".length() || refName.endsWith("/") || refName.contains("//") || refName.contains("..") || refName.contains("@{")) {
                return false;
            }
            for (int index = 0; index < refName.length(); index++) {
                char value = refName.charAt(index);
                if (value <= 0x20 || value >= 0x7f || value == '~' || value == '^' || value == ':' || value == '?' || value == '*' || value == '[' || value == '\\') {
                    return false;
                }
            }
            return true;
        }
    }




    private static final class ReceivePackStatusSerialization implements OutputSerialization {
        private final String unpackStatus;
        private final List<ReceiveCommandStatus> statuses;
        private final boolean sideBand64k;
        private int packetIndex;

        private ReceivePackStatusSerialization(
                String unpackStatus,
                List<ReceiveCommandStatus> statuses,
                boolean sideBand64k) {
            this.unpackStatus = unpackStatus;
            this.statuses = statuses;
            this.sideBand64k = sideBand64k;
        }

        @Override
        public void writeTo(GitBlockingWireTransport wire) throws IOException {
            while (packetIndex < statuses.size() + 2) {
                GitPktLine packet;
                if (packetIndex == 0) {
                    packet = new GitPktLine.Data(utf8("unpack " + unpackStatus + "\n"));
                } else if (packetIndex <= statuses.size()) {
                    packet = new GitPktLine.Data(utf8(receiveCommandStatusPayload(statuses.get(packetIndex - 1))));
                } else {
                    packet = GitPktLine.Control.FLUSH;
                }
                if (sideBand64k) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream(packet.length());
                    packet.writeTo(new OutputStreamBufferedByteOutput(bytes));
                    byte[] payload = bytes.toByteArray();
                    wire.writeSideBand(SideBand.DATA, payload, 0, payload.length);
                } else {
                    wire.writePacket(packet);
                }
                packetIndex++;
            }
            if (sideBand64k && packetIndex == statuses.size() + 2) {
                wire.writeFlush();
                packetIndex++;
            }
            wire.flush();
        }
    }

    private static String receiveCommandStatusPayload(ReceiveCommandStatus status) {
        return status.ok() ? "ok " + status.refName() + "\n" : "ng " + status.refName() + " " + status.message() + "\n";
    }
}
