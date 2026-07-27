package pro.deta.orion.git.nativestorage.upload;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import pro.deta.orion.git.common.GitFetchAccessRequest;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.common.GitRefResolver;
import pro.deta.orion.git.common.GitUploadStats;
import pro.deta.orion.git.nativestorage.object.LooseObject;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.pack.NoDeltaPackBuilder;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.parser.wire.GitFixedControlFrameReader;
import pro.deta.orion.git.parser.wire.pkt.GitPktLineWriter;
import pro.deta.orion.git.parser.wire.protocolv2.GitProtocolV2Line;
import pro.deta.orion.git.parser.wire.protocolv2.GitProtocolV2Request;
import pro.deta.orion.git.parser.wire.protocolv2.GitProtocolV2SectionParser;
import pro.deta.orion.git.parser.wire.sideband.GitSideBandBand;
import pro.deta.orion.git.parser.wire.sideband.GitSideBandMode;
import pro.deta.orion.git.parser.wire.sideband.GitSideBandWriter;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

public final class NativeUploadPackService {
    private static final int MAX_REQUEST_PACKETS = 1024;
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;

    private final ByteBufAllocator allocator;
    private final String repositoryName;
    private final LooseRefStore refs;
    private final LooseObjectStore objects;
    private final Optional<String> headTarget;
    private final Consumer<GitFetchAccessRequest> fetchAccessCheck;
    private final NativeLsRefsService lsRefsService;
    private final NativeFetchRequestParser fetchRequestParser;
    private final NativeObjectClosure objectClosure;
    private final NoDeltaPackBuilder packBuilder;
    private final GitPktLineWriter pktLineWriter;
    private final GitSideBandWriter sideBandWriter;

    public NativeUploadPackService(
            ByteBufAllocator allocator,
            String repositoryName,
            LooseRefStore refs,
            LooseObjectStore objects,
            Optional<String> headTarget,
            Consumer<GitFetchAccessRequest> fetchAccessCheck) {
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        this.repositoryName = Objects.requireNonNull(repositoryName, "repositoryName");
        this.refs = Objects.requireNonNull(refs, "refs");
        this.objects = Objects.requireNonNull(objects, "objects");
        this.headTarget = Objects.requireNonNull(headTarget, "headTarget");
        this.fetchAccessCheck = Objects.requireNonNull(fetchAccessCheck, "fetchAccessCheck");
        this.lsRefsService = new NativeLsRefsService(allocator);
        this.fetchRequestParser = new NativeFetchRequestParser(MAX_REQUEST_PACKETS);
        this.objectClosure = new NativeObjectClosure(objects);
        this.packBuilder = new NoDeltaPackBuilder();
        this.pktLineWriter = new GitPktLineWriter(allocator);
        this.sideBandWriter = new GitSideBandWriter(allocator, GitSideBandMode.SIDE_BAND_64K);
    }

    public Optional<GitUploadStats> serve(InputStream input, OutputStream output) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");

        writeCapabilities(output);
        while (true) {
            ByteBuf requestBuffer = readNextRequest(input);
            if (requestBuffer == null) {
                return Optional.empty();
            }
            try {
                GitProtocolV2Request request = GitProtocolV2SectionParser.read(requestBuffer);
                switch (request.command()) {
                    case "ls-refs" -> writeLsRefs(request, output);
                    case "fetch" -> {
                        return Optional.of(writeFetch(fetchRequestParser.parse(request), output));
                    }
                    default -> {
                        writeError(output, "unsupported protocol v2 command");
                        return Optional.empty();
                    }
                }
            } catch (RuntimeException e) {
                writeError(output, sanitizeError(e));
                return Optional.empty();
            } finally {
                requestBuffer.release();
            }
        }
    }

    public void writeCapabilities(OutputStream output) throws IOException {
        writePackets(output, List.of(
                pktLineWriter.writeTextLine("version 2"),
                pktLineWriter.writeTextLine("agent=orion-native"),
                pktLineWriter.writeTextLine("ls-refs=unborn"),
                pktLineWriter.writeTextLine("fetch"),
                pktLineWriter.writeTextLine("object-format=sha1"),
                pktLineWriter.writeFlush()));
    }

    public void writeLsRefs(GitProtocolV2Request request, OutputStream output) throws IOException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(output, "output");
        List<String> refPrefixes = new ArrayList<>();
        boolean includeSymrefs = false;
        boolean includeUnborn = false;
        for (GitProtocolV2Line argument : request.arguments()) {
            String line = argument.rawLine();
            if ("symrefs".equals(line)) {
                includeSymrefs = true;
            } else if ("unborn".equals(line)) {
                includeUnborn = true;
            } else if (line.startsWith("ref-prefix ")) {
                refPrefixes.add(line.substring("ref-prefix ".length()));
            }
        }
        writePackets(output, lsRefsService.advertise(
                refs,
                headTarget,
                includeSymrefs,
                includeUnborn,
                refPrefixes));
    }

    public GitUploadStats writeFetch(NativeFetchRequest request, OutputStream output) throws IOException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(output, "output");
        checkFetchAccess(request);

        List<LooseObject> selectedObjects = objectClosure.objectsFor(request.wants(), request.haves());
        byte[] pack = packBuilder.build(selectedObjects);
        writePackets(output, List.of(pktLineWriter.writeTextLine("packfile")));
        writeSideBandData(output, pack);
        writePackets(output, List.of(pktLineWriter.writeFlush()));
        output.flush();
        return new GitUploadStats(selectedObjects.size(), 0, pack.length);
    }

    private void checkFetchAccess(NativeFetchRequest request) {
        try {
            fetchAccessCheck.accept(new GitFetchAccessRequest(
                    repositoryName,
                    List.copyOf(request.wants()),
                    new SnapshotRefResolver(refs.snapshot())));
        } catch (SecurityException e) {
            throw new GitUploadPackException(
                    GitUploadPackException.Kind.ACCESS_DENIED,
                    "ACCESS_DENIED");
        }
    }

    private void writeSideBandData(OutputStream output, byte[] pack) throws IOException {
        ByteBuf payload = Unpooled.wrappedBuffer(pack);
        try {
            writePackets(output, sideBandWriter.write(GitSideBandBand.DATA, payload));
        } finally {
            payload.release();
        }
    }

    private static ByteBuf readNextRequest(InputStream input) throws IOException {
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        int packetCount = 0;
        int byteCount = 0;
        while (true) {
            byte[] header = readHeader(input);
            if (header == null) {
                return request.size() == 0 ? null : requestBuffer(request);
            }
            request.write(header);
            byteCount += header.length;
            int length = parseLength(header);
            if (length == 0 || length == 2) {
                return requestBuffer(request);
            }
            if (length == 1) {
                continue;
            }
            if (length == 3 || length < 4) {
                throw new GitUploadPackException(
                        GitUploadPackException.Kind.INVALID_REQUEST,
                        "Invalid pkt-line length");
            }
            if (length > GitFixedControlFrameReader.MAX_PKT_LINE_LENGTH) {
                throw new GitUploadPackException(
                        GitUploadPackException.Kind.INVALID_REQUEST,
                        "Pkt-line length exceeds Git pkt-line limit");
            }
            byte[] payload = input.readNBytes(length - 4);
            if (payload.length != length - 4) {
                throw new EOFException("Truncated Git protocol v2 request");
            }
            request.write(payload);
            byteCount += payload.length;
            packetCount++;
            if (packetCount > MAX_REQUEST_PACKETS || byteCount > MAX_REQUEST_BYTES) {
                throw new GitUploadPackException(
                        GitUploadPackException.Kind.INVALID_REQUEST,
                        "Protocol v2 request exceeds configured limits");
            }
        }
    }

    private static ByteBuf requestBuffer(ByteArrayOutputStream request) {
        return Unpooled.wrappedBuffer(request.toByteArray());
    }

    private static byte[] readHeader(InputStream input) throws IOException {
        byte[] header = input.readNBytes(4);
        if (header.length == 0) {
            return null;
        }
        if (header.length != 4) {
            throw new EOFException("Truncated Git pkt-line header");
        }
        return header;
    }

    private static int parseLength(byte[] header) {
        int value = 0;
        for (byte b : header) {
            int digit = Character.digit((char) b, 16);
            if (digit < 0) {
                throw new GitUploadPackException(
                        GitUploadPackException.Kind.INVALID_REQUEST,
                        "Invalid pkt-line length header");
            }
            value = (value << 4) | digit;
        }
        return value;
    }

    private void writeError(OutputStream output, String message) throws IOException {
        writePackets(output, List.of(pktLineWriter.writeTextLine("ERR " + message)));
        output.flush();
    }

    private static String sanitizeError(Throwable error) {
        if (error instanceof GitUploadPackException uploadPackException
                && uploadPackException.kind() == GitUploadPackException.Kind.ACCESS_DENIED) {
            return "ACCESS_DENIED";
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return "upload-pack failed";
        }
        return message.replaceAll("[\\r\\n]+", " ");
    }

    private static void writePackets(OutputStream output, List<ByteBuf> packets) throws IOException {
        for (ByteBuf packet : packets) {
            try {
                byte[] bytes = new byte[packet.readableBytes()];
                packet.getBytes(packet.readerIndex(), bytes);
                output.write(bytes);
            } finally {
                packet.release();
            }
        }
    }

    private static final class SnapshotRefResolver implements GitRefResolver {
        private final Map<GitObjectId, String> refsByObjectId;

        private SnapshotRefResolver(Map<String, String> refs) {
            Map<GitObjectId, String> result = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : refs.entrySet()) {
                result.putIfAbsent(GitObjectId.of(entry.getValue()), branchName(entry.getKey()));
            }
            refsByObjectId = Map.copyOf(result);
        }

        @Override
        public Map<GitObjectId, String> resolveBranchNames(Collection<GitObjectId> objectIds) {
            Map<GitObjectId, String> result = new LinkedHashMap<>();
            for (GitObjectId objectId : objectIds) {
                String refName = refsByObjectId.get(objectId);
                if (refName != null) {
                    result.put(objectId, refName);
                }
            }
            return result;
        }

        private static String branchName(String refName) {
            String prefix = "refs/heads/";
            if (refName.startsWith(prefix)) {
                return refName.substring(prefix.length());
            }
            return refName;
        }
    }
}
