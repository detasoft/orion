package pro.deta.orion.git.nativestorage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import pro.deta.orion.git.common.GitFetchAccessRequest;
import pro.deta.orion.git.common.GitOperationException;
import pro.deta.orion.git.common.GitReceiveRequest;
import pro.deta.orion.git.common.GitRepository;
import pro.deta.orion.git.common.GitUploadRequest;
import pro.deta.orion.git.common.GitUploadStats;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.pack.PackIngestor;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.nativestorage.service.NativeReceivePackService;
import pro.deta.orion.git.nativestorage.service.ReceivePackPolicy;
import pro.deta.orion.git.nativestorage.service.ReceiveResult;
import pro.deta.orion.git.nativestorage.upload.NativeUploadPackService;
import pro.deta.orion.git.parser.wire.GitFixedControlFrameReader;
import pro.deta.orion.git.parser.wire.receivepack.ReceivePackCommandParser;
import pro.deta.orion.git.parser.wire.receivepack.ReceivePackCommandSection;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public final class NativeGitRepository implements GitRepository {
    private static final long MAX_RECEIVE_PACK_BYTES = 100L * 1024L * 1024L;
    private static final int MAX_RECEIVE_COMMAND_BYTES = 1024 * 1024;

    private final String name;
    private final String description;
    private final LooseRefStore refs;
    private final LooseObjectStore objects;
    private final Optional<String> headTarget;
    private final ByteBufAllocator allocator;
    private final Consumer<GitFetchAccessRequest> fetchAccessCheck;

    public NativeGitRepository(
            String name,
            String description,
            LooseRefStore refs,
            LooseObjectStore objects,
            Optional<String> headTarget) {
        this(
                name,
                description,
                refs,
                objects,
                headTarget,
                UnpooledByteBufAllocator.DEFAULT,
                ignored -> {
                });
    }

    private NativeGitRepository(
            String name,
            String description,
            LooseRefStore refs,
            LooseObjectStore objects,
            Optional<String> headTarget,
            ByteBufAllocator allocator,
            Consumer<GitFetchAccessRequest> fetchAccessCheck) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = Objects.requireNonNullElse(description, "");
        this.refs = Objects.requireNonNull(refs, "refs");
        this.objects = Objects.requireNonNull(objects, "objects");
        this.headTarget = Objects.requireNonNull(headTarget, "headTarget");
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        this.fetchAccessCheck = Objects.requireNonNull(fetchAccessCheck, "fetchAccessCheck");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public GitRepository withFetchAccessCheck(Consumer<GitFetchAccessRequest> fetchAccessCheck) {
        return new NativeGitRepository(
                name,
                description,
                refs,
                objects,
                headTarget,
                allocator,
                fetchAccessCheck);
    }

    @Override
    public void upload(GitUploadRequest request, InputStream input, OutputStream output, OutputStream error)
            throws IOException {
        Objects.requireNonNull(request, "request");
        Optional<GitUploadStats> stats = new NativeUploadPackService(
                allocator,
                name,
                refs,
                objects,
                headTarget,
                fetchAccessCheck)
                .serve(input, output);
        stats.ifPresent(request.afterUpload());
    }

    @Override
    public void receive(GitReceiveRequest request, InputStream input, OutputStream output, OutputStream error)
            throws IOException, GitOperationException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        NativeReceivePackService receivePackService = new NativeReceivePackService(
                refs,
                objects,
                new PackIngestor(MAX_RECEIVE_PACK_BYTES),
                ReceivePackPolicy.conservative(),
                allocator);
        writePackets(output, receivePackService.advertise());

        ByteBuf commandBuffer = readCommandSection(input);
        ByteBuf packBuffer = Unpooled.EMPTY_BUFFER;
        try {
            ReceivePackCommandSection commandSection = new ReceivePackCommandParser().read(commandBuffer);
            if (expectsPack(commandSection)) {
                packBuffer = readPack(input);
            }
            ReceiveResult result = receivePackService.receive(commandSection, packBuffer);
            writePackets(output, receivePackService.reportStatus(commandSection, result));
            output.flush();
            request.afterReceive().accept(result.refUpdates());
        } catch (RuntimeException e) {
            throw new GitOperationException("Native receive-pack failed: " + e.getMessage(), e);
        } finally {
            commandBuffer.release();
            packBuffer.release();
        }
    }

    @Override
    public <T> Optional<T> unwrap(Class<T> repositoryType) {
        Objects.requireNonNull(repositoryType, "repositoryType");
        if (repositoryType.isInstance(this)) {
            return Optional.of(repositoryType.cast(this));
        }
        if (repositoryType.isInstance(refs)) {
            return Optional.of(repositoryType.cast(refs));
        }
        if (repositoryType.isInstance(objects)) {
            return Optional.of(repositoryType.cast(objects));
        }
        return Optional.empty();
    }

    @Override
    public void close() {
    }

    private ByteBuf readCommandSection(InputStream input) throws IOException {
        ByteBuf commandBuffer = allocator.buffer();
        int byteCount = 0;
        boolean complete = false;
        try {
            while (!complete) {
                byte[] header = input.readNBytes(4);
                if (header.length != 4) {
                    throw new IOException("Truncated receive-pack command header");
                }
                commandBuffer.writeBytes(header);
                byteCount += header.length;
                int length = parseLength(header);
                if (length == 0) {
                    complete = true;
                } else if (length < 4 || length > GitFixedControlFrameReader.MAX_PKT_LINE_LENGTH) {
                    complete = true;
                } else {
                    byte[] payload = input.readNBytes(length - 4);
                    if (payload.length != length - 4) {
                        throw new IOException("Truncated receive-pack command payload");
                    }
                    commandBuffer.writeBytes(payload);
                    byteCount += payload.length;
                }
                if (byteCount > MAX_RECEIVE_COMMAND_BYTES) {
                    throw new IOException("Receive-pack command section exceeds configured limit");
                }
            }
            return commandBuffer;
        } catch (IOException | RuntimeException e) {
            commandBuffer.release();
            throw e;
        }
    }

    private static boolean expectsPack(ReceivePackCommandSection commandSection) {
        for (var command : commandSection.commands()) {
            if (!command.isDelete()) {
                return true;
            }
        }
        return false;
    }

    private ByteBuf readPack(InputStream input) throws IOException {
        ByteBuf packBuffer = allocator.buffer();
        try {
            byte[] signature = readPackBytes(input, packBuffer, 4, "signature");
            if (signature[0] != 'P' || signature[1] != 'A' || signature[2] != 'C' || signature[3] != 'K') {
                throw new IOException("Receive-pack pack missing PACK signature");
            }
            int version = readPackInt(input, packBuffer, "version");
            if (version != 2) {
                throw new IOException("Unsupported receive-pack PACK version: " + version);
            }
            long objectCount = Integer.toUnsignedLong(readPackInt(input, packBuffer, "object count"));
            for (long i = 0; i < objectCount; i++) {
                readPackedObject(input, packBuffer);
            }
            readPackBytes(input, packBuffer, 20, "checksum");
            return packBuffer;
        } catch (IOException | RuntimeException e) {
            packBuffer.release();
            throw e;
        }
    }

    private void readPackedObject(InputStream input, ByteBuf packBuffer) throws IOException {
        int header = readPackByte(input, packBuffer, "object header");
        int type = (header >> 4) & 0x7;
        while ((header & 0x80) != 0) {
            header = readPackByte(input, packBuffer, "object header");
        }
        if (type == 6) {
            int offset = readPackByte(input, packBuffer, "offset delta base");
            while ((offset & 0x80) != 0) {
                offset = readPackByte(input, packBuffer, "offset delta base");
            }
        } else if (type == 7) {
            readPackBytes(input, packBuffer, 20, "ref delta base");
        }
        readCompressedObject(input, packBuffer);
    }

    private void readCompressedObject(InputStream input, ByteBuf packBuffer) throws IOException {
        Inflater inflater = new Inflater();
        byte[] compressedByte = new byte[1];
        byte[] inflated = new byte[8192];
        try {
            while (!inflater.finished()) {
                compressedByte[0] = (byte) readPackByte(input, packBuffer, "compressed object");
                inflater.setInput(compressedByte);
                while (!inflater.needsInput() && !inflater.finished()) {
                    int inflatedBytes = inflater.inflate(inflated);
                    if (inflatedBytes == 0 && inflater.needsDictionary()) {
                        throw new IOException("Receive-pack compressed object requires a zlib dictionary");
                    }
                }
            }
        } catch (DataFormatException e) {
            throw new IOException("Malformed receive-pack compressed object", e);
        } finally {
            inflater.end();
        }
    }

    private int readPackInt(InputStream input, ByteBuf packBuffer, String context) throws IOException {
        byte[] bytes = readPackBytes(input, packBuffer, 4, context);
        return ((bytes[0] & 0xff) << 24)
                | ((bytes[1] & 0xff) << 16)
                | ((bytes[2] & 0xff) << 8)
                | (bytes[3] & 0xff);
    }

    private byte[] readPackBytes(InputStream input, ByteBuf packBuffer, int count, String context) throws IOException {
        byte[] bytes = input.readNBytes(count);
        if (bytes.length != count) {
            throw new IOException("Truncated receive-pack pack " + context);
        }
        packBuffer.writeBytes(bytes);
        ensurePackLimit(packBuffer);
        return bytes;
    }

    private int readPackByte(InputStream input, ByteBuf packBuffer, String context) throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new IOException("Truncated receive-pack pack " + context);
        }
        packBuffer.writeByte(value);
        ensurePackLimit(packBuffer);
        return value;
    }

    private static void ensurePackLimit(ByteBuf packBuffer) throws IOException {
        if (packBuffer.readableBytes() > MAX_RECEIVE_PACK_BYTES) {
            throw new IOException("Receive-pack pack exceeds configured limit");
        }
    }

    private static int parseLength(byte[] header) throws IOException {
        int value = 0;
        for (byte b : header) {
            int digit = Character.digit((char) b, 16);
            if (digit < 0) {
                throw new IOException("Invalid receive-pack pkt-line length header");
            }
            value = (value << 4) | digit;
        }
        return value;
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
}
