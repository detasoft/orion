package pro.deta.orion.git.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.git.parser.wire.GitBlockingWireTransport;
import pro.deta.orion.git.parser.wire.GitPktLineFormatException;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.BufferedByteOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class GitBlockingClientWire {
    private static final int MAXIMUM_REPORT_STATUS_BYTES = 1024 * 1024;

    private final GitBlockingWireTransport wire;

    GitBlockingClientWire(GitClientTransportSession session) {
        wire = new GitBlockingWireTransport(session.input(), session.output());
    }

    GitRemoteAdvertisement readAdvertisement()
            throws IOException, GitClientProtocolException {
        List<String> lines = new ArrayList<>();
        while (true) {
            GitPktLine packet = readPacket(
                    GitClientFailure.Phase.ADVERTISEMENT);
            ByteBuf payload = wire.payloadBuffer(packet);
            try {
                if (packet == GitPktLine.Control.FLUSH) {
                    break;
                }
                requireData(packet, GitClientFailure.Phase.ADVERTISEMENT);
                String line = ascii(payload,
                        GitClientFailure.Phase.ADVERTISEMENT);
                if (lines.isEmpty() && "version 1".equals(stripLf(line))) {
                    continue;
                }
                rejectServerError(line, GitClientFailure.Phase.ADVERTISEMENT);
                lines.add(stripLf(line));
            } finally {
                payload.release();
            }
        }
        return parseAdvertisement(lines);
    }

    void writeUploadRequest(
            GitUploadPackRequest request,
            GitRemoteAdvertisement advertisement)
            throws IOException, GitClientProtocolException {
        Set<String> capabilities = advertisement.capabilities();
        List<String> selected = new ArrayList<>();
        if (capabilities.contains("side-band-64k")) {
            selected.add("side-band-64k");
        } else if (capabilities.contains("side-band")) {
            selected.add("side-band");
        }
        if (capabilities.contains("multi_ack_detailed")) {
            selected.add("multi_ack_detailed");
        }
        for (int index = 0; index < request.wants().size(); index++) {
            String suffix = index == 0 && !selected.isEmpty()
                    ? " " + String.join(" ", selected)
                    : "";
            wire.writeTextLine("want " + request.wants().get(index) + suffix);
        }
        wire.writeFlush();
        for (String have : request.haves()) {
            wire.writeTextLine("have " + have);
        }
        wire.writeTextLine("done");
        wire.flush();
    }

    long readUploadPack(
            GitUploadPackRequest request,
            GitRemoteAdvertisement advertisement,
            long maximumPackBytes)
            throws IOException, GitClientProtocolException {
        boolean sideBand = advertisement.capabilities().contains("side-band-64k")
                || advertisement.capabilities().contains("side-band");
        while (true) {
            GitPktLine packet = readPacket(
                    GitClientFailure.Phase.NEGOTIATION);
            if (!(packet instanceof GitPktLine.Data)) {
                throw protocolFailure(
                        GitClientFailure.Kind.MALFORMED_RESPONSE,
                        GitClientFailure.Phase.NEGOTIATION,
                        "Expected upload-pack negotiation response");
            }
            if (sideBand && isSideBand(((GitPktLine.Data) packet).content())) {
                return readSideBandPack(packet, request, maximumPackBytes);
            }
            ByteBuf payload = wire.payloadBuffer(packet);
            try {
                String line = stripLf(ascii(
                        payload, GitClientFailure.Phase.NEGOTIATION));
                rejectServerError(line, GitClientFailure.Phase.NEGOTIATION);
                if (!"NAK".equals(line) && !line.startsWith("ACK ")) {
                    throw protocolFailure(
                            GitClientFailure.Kind.MALFORMED_RESPONSE,
                            GitClientFailure.Phase.NEGOTIATION,
                            "Unexpected upload-pack negotiation response");
                }
                if (line.startsWith("ACK ") && (line.endsWith(" common")
                        || line.endsWith(" ready") || line.endsWith(" continue"))) {
                    continue;
                }
            } finally {
                payload.release();
            }
            if (!sideBand) {
                return readRawPack(request, maximumPackBytes);
            }
        }
    }

    private long readRawPack(
            GitUploadPackRequest request,
            long maximumPackBytes)
            throws IOException, GitClientProtocolException {
        long total = 0;
        while (true) {
            ByteBuf buffer = Unpooled.buffer(GitBlockingWireTransport.BUFFER_CAPACITY);
            try {
                int read = wire.readRawInto(
                        buffer, GitBlockingWireTransport.BUFFER_CAPACITY);
                if (read == 0) {
                    request.packTarget().flush();
                    return total;
                }
                total += read;
                if (total > maximumPackBytes) {
                    throw protocolFailure(
                            GitClientFailure.Kind.PACK_SIZE_LIMIT_EXCEEDED,
                            GitClientFailure.Phase.PACK_TRANSFER,
                            "Remote pack exceeds configured size limit");
                }
                request.packTarget().write(buffer);
            } finally {
                buffer.release();
            }
        }
    }

    void writeReceiveRequest(
            GitReceivePackRequest request,
            GitRemoteAdvertisement advertisement,
            BufferedByteOutput output,
            long maximumPackBytes)
            throws IOException, GitClientProtocolException {
        requireCapability(
                advertisement, "report-status", GitClientFailure.Phase.NEGOTIATION);
        List<String> selected = new ArrayList<>();
        selected.add("report-status");
        if (request.atomic()) {
            requireCapability(
                    advertisement,
                    "atomic",
                    GitClientFailure.Phase.NEGOTIATION);
            selected.add("atomic");
        }
        boolean deletesRef = false;
        for (GitReceivePackRequest.Command command : request.commands()) {
            if (GitClientValidation.NULL_ID.equalsIgnoreCase(
                    command.newObjectId())) {
                deletesRef = true;
                break;
            }
        }
        if (deletesRef) {
            requireCapability(
                    advertisement,
                    "delete-refs",
                    GitClientFailure.Phase.NEGOTIATION);
            selected.add("delete-refs");
        }
        boolean sideBand = advertisement.capabilities().contains("side-band-64k");
        if (sideBand) {
            selected.add("side-band-64k");
        }
        for (int index = 0; index < request.commands().size(); index++) {
            GitReceivePackRequest.Command command = request.commands().get(index);
            String suffix = index == 0
                    ? "\0" + String.join(" ", selected)
                    : "";
            wire.writeTextLine(command.oldObjectId()
                    + " " + command.newObjectId()
                    + " " + command.refName()
                    + suffix);
        }
        wire.writeFlush();
        try {
            request.packSource().writeTo(new LimitedPackOutput(
                    output, maximumPackBytes));
        } catch (PackSizeLimitException error) {
            throw protocolFailure(
                    GitClientFailure.Kind.PACK_SIZE_LIMIT_EXCEEDED,
                    GitClientFailure.Phase.PACK_TRANSFER,
                    "Local pack exceeds configured size limit",
                    error);
        }
        output.flush();
    }

    GitReceivePackResult readReceiveStatus(
            GitRemoteAdvertisement advertisement,
            GitReceivePackRequest request)
            throws IOException, GitClientProtocolException {
        boolean sideBand = advertisement.capabilities().contains("side-band-64k");
        List<String> lines = sideBand
                ? readSideBandStatus()
                : readDirectStatus();
        if (lines.isEmpty() || !lines.getFirst().startsWith("unpack ")) {
            throw protocolFailure(
                    GitClientFailure.Kind.MALFORMED_RESPONSE,
                    GitClientFailure.Phase.REPORT_STATUS,
                    "Receive-pack response is missing unpack status");
        }
        String unpackStatus = lines.getFirst().substring("unpack ".length());
        if (unpackStatus.isBlank()) {
            throw protocolFailure(
                    GitClientFailure.Kind.MALFORMED_RESPONSE,
                    GitClientFailure.Phase.REPORT_STATUS,
                    "Receive-pack response is missing unpack status");
        }
        List<GitReceivePackResult.RefStatus> refs = new ArrayList<>();
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.startsWith("ok ")) {
                refs.add(new GitReceivePackResult.RefStatus(
                        line.substring(3), true, ""));
                continue;
            }
            if (line.startsWith("ng ")) {
                int separator = line.indexOf(' ', 3);
                if (separator > 3) {
                    refs.add(new GitReceivePackResult.RefStatus(
                            line.substring(3, separator),
                            false,
                            line.substring(separator + 1)));
                    continue;
                }
            }
            throw protocolFailure(
                    GitClientFailure.Kind.MALFORMED_RESPONSE,
                    GitClientFailure.Phase.REPORT_STATUS,
                    "Malformed receive-pack ref status");
        }
        requireExpectedRefStatuses(request.commands(), refs);
        return new GitReceivePackResult(advertisement, unpackStatus, refs);
    }

    private static void requireExpectedRefStatuses(
            List<GitReceivePackRequest.Command> commands,
            List<GitReceivePackResult.RefStatus> statuses)
            throws GitClientProtocolException {
        Set<String> expected = new LinkedHashSet<>();
        for (GitReceivePackRequest.Command command : commands) {
            if (!expected.add(command.refName())) {
                throw malformedStatus();
            }
        }
        Set<String> received = new LinkedHashSet<>();
        for (GitReceivePackResult.RefStatus status : statuses) {
            if (!received.add(status.refName()) || !expected.contains(status.refName())) {
                throw malformedStatus();
            }
        }
        if (received.size() != expected.size()) {
            throw malformedStatus();
        }
    }

    private long readSideBandPack(
            GitPktLine first,
            GitUploadPackRequest request,
            long maximumPackBytes)
            throws IOException, GitClientProtocolException {
        long total = 0;
        GitPktLine packet = first;
        while (true) {
            ByteBuf payload = wire.payloadBuffer(packet);
            try {
                if (packet == GitPktLine.Control.FLUSH) {
                    request.packTarget().flush();
                    return total;
                }
                requireData(packet, GitClientFailure.Phase.PACK_TRANSFER);
                if (!payload.isReadable()) {
                    throw protocolFailure(
                            GitClientFailure.Kind.MALFORMED_RESPONSE,
                            GitClientFailure.Phase.PACK_TRANSFER,
                            "Side-band packet is empty");
                }
                int channel = payload.getUnsignedByte(payload.readerIndex());
                ByteBuf data = payload.slice(
                        payload.readerIndex() + 1,
                        payload.readableBytes() - 1);
                if (channel == 1) {
                    total += data.readableBytes();
                    if (total > maximumPackBytes) {
                        throw protocolFailure(
                                GitClientFailure.Kind.PACK_SIZE_LIMIT_EXCEEDED,
                                GitClientFailure.Phase.PACK_TRANSFER,
                                "Remote pack exceeds configured size limit");
                    }
                    request.packTarget().write(data);
                } else if (channel == 2) {
                    request.progress().accept(data.toString(StandardCharsets.UTF_8));
                } else if (channel == 3) {
                    throw protocolFailure(
                            GitClientFailure.Kind.SIDE_BAND_ERROR,
                            GitClientFailure.Phase.PACK_TRANSFER,
                            sanitized(data));
                } else {
                    throw protocolFailure(
                            GitClientFailure.Kind.MALFORMED_RESPONSE,
                            GitClientFailure.Phase.PACK_TRANSFER,
                            "Unknown Git side-band channel");
                }
            } finally {
                payload.release();
            }
            packet = readPacket(GitClientFailure.Phase.PACK_TRANSFER);
        }
    }

    private List<String> readDirectStatus()
            throws IOException, GitClientProtocolException {
        List<String> lines = new ArrayList<>();
        while (true) {
            GitPktLine packet = readPacket(
                    GitClientFailure.Phase.REPORT_STATUS);
            ByteBuf payload = wire.payloadBuffer(packet);
            try {
                if (packet == GitPktLine.Control.FLUSH) {
                    return List.copyOf(lines);
                }
                requireData(packet, GitClientFailure.Phase.REPORT_STATUS);
                lines.add(stripLf(ascii(
                        payload, GitClientFailure.Phase.REPORT_STATUS)));
            } finally {
                payload.release();
            }
        }
    }

    private List<String> readSideBandStatus()
            throws IOException, GitClientProtocolException {
        ByteArrayOutputStream status = new ByteArrayOutputStream();
        while (true) {
            GitPktLine packet = readPacket(
                    GitClientFailure.Phase.REPORT_STATUS);
            ByteBuf payload = wire.payloadBuffer(packet);
            try {
                if (packet == GitPktLine.Control.FLUSH) {
                    return parsePacketLines(status.toByteArray());
                }
                requireData(packet, GitClientFailure.Phase.REPORT_STATUS);
                if (!payload.isReadable()) {
                    throw protocolFailure(
                            GitClientFailure.Kind.MALFORMED_RESPONSE,
                            GitClientFailure.Phase.REPORT_STATUS,
                            "Side-band status packet is empty");
                }
                int channel = payload.getUnsignedByte(payload.readerIndex());
                ByteBuf data = payload.slice(
                        payload.readerIndex() + 1,
                        payload.readableBytes() - 1);
                if (channel == 1) {
                    if (status.size() + data.readableBytes()
                            > MAXIMUM_REPORT_STATUS_BYTES) {
                        throw protocolFailure(
                                GitClientFailure.Kind.MALFORMED_RESPONSE,
                                GitClientFailure.Phase.REPORT_STATUS,
                                "Receive-pack status exceeds size limit");
                    }
                    byte[] bytes = new byte[data.readableBytes()];
                    data.getBytes(data.readerIndex(), bytes);
                    status.writeBytes(bytes);
                } else if (channel == 3) {
                    throw protocolFailure(
                            GitClientFailure.Kind.SIDE_BAND_ERROR,
                            GitClientFailure.Phase.REPORT_STATUS,
                            sanitized(data));
                } else if (channel != 2) {
                    throw protocolFailure(
                            GitClientFailure.Kind.MALFORMED_RESPONSE,
                            GitClientFailure.Phase.REPORT_STATUS,
                            "Unknown Git side-band channel");
                }
            } finally {
                payload.release();
            }
        }
    }

    private static List<String> parsePacketLines(byte[] bytes)
            throws GitClientProtocolException {
        List<String> lines = new ArrayList<>();
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(bytes))) {
            while (true) {
                GitPktLine packet = GitPktLine.readNextFrom(input)
                        .orElseThrow(() -> new EOFException("Expected a Git pkt-line"));
                switch (packet) {
                    case GitPktLine.Control.FLUSH -> {
                        if (input.buffer() != null) {
                            throw malformedStatus();
                        }
                        return List.copyOf(lines);
                    }
                    case GitPktLine.Data data ->
                            lines.add(stripLf(new String(data.content(), StandardCharsets.UTF_8)));
                    case GitPktLine.Control.DELIMITER, GitPktLine.Control.RESPONSE_END -> throw malformedStatus();
                }
            }
        } catch (IOException error) {
            throw malformedStatus();
        }
    }

    private static GitRemoteAdvertisement parseAdvertisement(List<String> lines)
            throws GitClientProtocolException {
        if (lines.isEmpty()) {
            throw protocolFailure(
                    GitClientFailure.Kind.MALFORMED_RESPONSE,
                    GitClientFailure.Phase.ADVERTISEMENT,
                    "Remote Git advertisement is empty");
        }
        Set<String> capabilities = new LinkedHashSet<>();
        Map<String, RefBuilder> refs = new LinkedHashMap<>();
        boolean capabilitiesRead = false;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.startsWith("shallow ")) {
                validateShallowAdvertisement(line);
                continue;
            }
            if (!capabilitiesRead) {
                capabilitiesRead = true;
                int separator = line.indexOf('\0');
                if (separator >= 0) {
                    String capabilityText = line.substring(separator + 1).trim();
                    line = line.substring(0, separator);
                    if (!capabilityText.isEmpty()) {
                        capabilities.addAll(List.of(capabilityText.split(" ")));
                    }
                }
            }
            int separator = line.indexOf(' ');
            if (separator != 40 || line.length() <= 41) {
                throw malformedAdvertisement();
            }
            String objectId = line.substring(0, separator);
            String name = line.substring(separator + 1);
            try {
                GitClientValidation.requireObjectId(objectId, "objectId");
                if (GitClientValidation.NULL_ID.equals(objectId)
                        && "capabilities^{}".equals(name)) {
                    continue;
                }
                if (name.endsWith("^{}")) {
                    String baseName = name.substring(0, name.length() - 3);
                    RefBuilder base = refs.get(baseName);
                    if (base == null) {
                        throw malformedAdvertisement();
                    }
                    base.peeledObjectId = objectId;
                } else {
                    GitClientValidation.requireAdvertisedRefName(name, "refName");
                    if (refs.putIfAbsent(
                            name, new RefBuilder(objectId, name)) != null) {
                        throw malformedAdvertisement();
                    }
                }
            } catch (IllegalArgumentException error) {
                throw malformedAdvertisement();
            }
        }
        List<GitRemoteAdvertisement.Ref> outputRefs = new ArrayList<>();
        for (RefBuilder ref : refs.values()) {
            outputRefs.add(new GitRemoteAdvertisement.Ref(
                    ref.objectId,
                    ref.name,
                    Optional.ofNullable(ref.peeledObjectId)));
        }
        return new GitRemoteAdvertisement(capabilities, outputRefs);
    }

    private static void validateShallowAdvertisement(String line)
            throws GitClientProtocolException {
        try {
            GitClientValidation.requireObjectId(
                    line.substring("shallow ".length()), "objectId");
        } catch (IllegalArgumentException error) {
            throw malformedAdvertisement();
        }
    }

    private GitPktLine readPacket(
            GitClientFailure.Phase phase)
            throws IOException, GitClientProtocolException {
        try {
            return wire.readPacket();
        } catch (EOFException error) {
            throw protocolFailure(
                    GitClientFailure.Kind.UNEXPECTED_END_OF_STREAM,
                    phase,
                    "Remote Git session ended unexpectedly",
                    error);
        } catch (InterruptedIOException error) {
            throw protocolFailure(
                    GitClientFailure.Kind.TIMEOUT,
                    phase,
                    true,
                    "Remote Git operation timed out",
                    error);
        } catch (GitPktLineFormatException error) {
            throw protocolFailure(
                    GitClientFailure.Kind.MALFORMED_RESPONSE,
                    phase,
                    "Failed to read Git wire response",
                    error);
        }
    }

    private static void requireData(
            GitPktLine packet,
            GitClientFailure.Phase phase) throws GitClientProtocolException {
        if (!(packet instanceof GitPktLine.Data)) {
            throw protocolFailure(
                    GitClientFailure.Kind.MALFORMED_RESPONSE,
                    phase,
                    "Unexpected Git wire control packet");
        }
    }

    private static String ascii(ByteBuf payload, GitClientFailure.Phase phase)
            throws GitClientProtocolException {
        for (int index = payload.readerIndex();
                index < payload.writerIndex(); index++) {
            int value = payload.getUnsignedByte(index);
            if (value != 0 && value != '\n'
                    && (value < 0x20 || value >= 0x7f)) {
                throw protocolFailure(
                        GitClientFailure.Kind.MALFORMED_RESPONSE,
                        phase,
                        "Git response contains invalid text bytes");
            }
        }
        return payload.toString(StandardCharsets.US_ASCII);
    }

    private static void requireCapability(
            GitRemoteAdvertisement advertisement,
            String capability,
            GitClientFailure.Phase phase) throws GitClientProtocolException {
        if (!advertisement.capabilities().contains(capability)) {
            throw protocolFailure(
                    GitClientFailure.Kind.CAPABILITY_MISSING,
                    phase,
                    "Remote Git service does not support " + capability);
        }
    }

    private static void rejectServerError(
            String line,
            GitClientFailure.Phase phase) throws GitClientProtocolException {
        if (line.startsWith("ERR ")) {
            throw protocolFailure(
                    GitClientFailure.Kind.SERVER_ERROR,
                    phase,
                    sanitized(line.substring(4)));
        }
    }

    private static boolean isSideBand(byte[] payload) {
        if (payload.length == 0) {
            return false;
        }
        int channel = payload[0] & 0xff;
        return channel >= 1 && channel <= 3;
    }

    private static String stripLf(String value) {
        return value.endsWith("\n")
                ? value.substring(0, value.length() - 1)
                : value;
    }

    private static String sanitized(ByteBuf data) {
        return sanitized(data.toString(StandardCharsets.UTF_8));
    }

    private static String sanitized(String value) {
        String cleaned = value.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "?").trim();
        if (cleaned.isBlank()) {
            return "Remote Git service reported an error";
        }
        return cleaned.length() <= 512 ? cleaned : cleaned.substring(0, 512);
    }

    private static GitClientProtocolException malformedAdvertisement() {
        return protocolFailure(
                GitClientFailure.Kind.MALFORMED_RESPONSE,
                GitClientFailure.Phase.ADVERTISEMENT,
                "Malformed remote Git advertisement");
    }

    private static GitClientProtocolException malformedStatus() {
        return protocolFailure(
                GitClientFailure.Kind.MALFORMED_RESPONSE,
                GitClientFailure.Phase.REPORT_STATUS,
                "Malformed receive-pack report status");
    }

    private static GitClientProtocolException protocolFailure(
            GitClientFailure.Kind kind,
            GitClientFailure.Phase phase,
            String message) {
        return protocolFailure(kind, phase, message, null);
    }

    private static GitClientProtocolException protocolFailure(
            GitClientFailure.Kind kind,
            GitClientFailure.Phase phase,
            boolean retryable,
            String message,
            Throwable cause) {
        return new GitClientProtocolException(new GitClientFailure(
                kind, phase, retryable, message, cause));
    }

    private static GitClientProtocolException protocolFailure(
            GitClientFailure.Kind kind,
            GitClientFailure.Phase phase,
            String message,
            Throwable cause) {
        return new GitClientProtocolException(new GitClientFailure(
                kind, phase, false, message, cause));
    }

    private static final class RefBuilder {
        private final String objectId;
        private final String name;
        private String peeledObjectId;

        private RefBuilder(String objectId, String name) {
            this.objectId = objectId;
            this.name = name;
        }
    }

    private static final class LimitedPackOutput implements BufferedByteOutput {
        private final BufferedByteOutput delegate;
        private final long maximumBytes;
        private long written;

        private LimitedPackOutput(
                BufferedByteOutput delegate,
                long maximumBytes) {
            this.delegate = delegate;
            this.maximumBytes = maximumBytes;
        }

        @Override
        public void write(ByteBuf buffer) throws IOException {
            int length = buffer.readableBytes();
            if (written + length > maximumBytes) {
                throw new PackSizeLimitException();
            }
            delegate.write(buffer);
            written += length;
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }
    }

    private static final class PackSizeLimitException extends IOException {
    }
}
