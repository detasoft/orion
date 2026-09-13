package pro.deta.orion.agent.server.command;

import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.AgentProtocolCodec;
import pro.deta.orion.agent.protocol.AgentProtocolException;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.CommandId;
import pro.deta.orion.agent.protocol.SessionId;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongFunction;

/** Durable command identities and per-session sequence high-water marks. */
final class FileSystemCommandLedger implements AutoCloseable {
    private static final int MAGIC = 0x4f52434d;
    private static final int VERSION = 1;
    private static final int MAX_RECORD_BYTES = AgentProtocolLimits.HARD_MAX_MESSAGE_BYTES + 8192;

    private final Path root;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final AgentProtocolCodec codec = new AgentProtocolCodec(AgentProtocolLimits.defaults());
    private final Map<CommandId, Entry> entries = new HashMap<>();
    private final Map<SessionId, Long> highWater = new HashMap<>();
    private boolean closed;
    private boolean indeterminate;

    FileSystemCommandLedger(Path root) throws IOException {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        Files.createDirectories(this.root);
        try (FileChannel parent = FileChannel.open(this.root.getParent(), StandardOpenOption.READ)) {
            parent.force(true);
        }
        lockChannel = FileChannel.open(this.root.resolve(".command-ledger.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired = null;
        try {
            acquired = lockChannel.tryLock();
            if (acquired == null) {
                throw new IOException("Command ledger is already owned");
            }
            lock = acquired;
            try (var files = Files.newDirectoryStream(this.root, "*.command")) {
                for (Path file : files) {
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                            || Files.size(file) > MAX_RECORD_BYTES) {
                        throw new IOException("Command record is not a bounded regular file");
                    }
                    byte[] bytes = Files.readAllBytes(file);
                    Entry entry = decode(bytes);
                    if (!file.getFileName().toString().equals(fileName(entry.commandId()))
                            || entries.putIfAbsent(entry.commandId(), entry) != null) {
                        throw new IOException("Command ledger contains conflicting identities");
                    }
                    long sequence = sequence(entry.message());
                    if (sequence > 0) {
                        highWater.merge(entry.sessionId(), sequence, Math::max);
                    }
                }
            }
        } catch (IOException | RuntimeException failure) {
            if (acquired != null) {
                acquired.release();
            }
            lockChannel.close();
            throw failure;
        }
    }

    synchronized Entry reserve(
            AgentId agentId, CommandId commandId, SessionId sessionId,
            LongFunction<AgentMessage> messageFactory) throws IOException, AgentProtocolException {
        requireOpen();
        if (entries.containsKey(commandId)) {
            throw new IllegalArgumentException("Command ID already exists");
        }
        long next = highWater.getOrDefault(sessionId, 0L) + 1;
        if (next <= 0 || next == Long.MAX_VALUE) {
            throw new IllegalStateException("Session operation sequence exhausted");
        }
        AgentMessage message = messageFactory.apply(next);
        if (!commandId.equals(commandId(message)) || !sessionId.equals(sessionId(message))) {
            throw new IllegalArgumentException("Command factory changed its identity");
        }
        if (message instanceof AgentMessage.StartSession) {
            for (Entry entry : entries.values()) {
                if (entry.sessionId().equals(sessionId)) {
                    throw new IllegalArgumentException("Session ID already has a command");
                }
            }
        }
        byte[] encoded = codec.encode(message);
        Entry entry = new Entry(agentId, message, encoded);
        byte[] stored = encode(entry);
        Path target = root.resolve(fileName(commandId));
        Path temporary = root.resolve(".pending-" + UUID.randomUUID());
        try (FileChannel channel = FileChannel.open(temporary,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(stored);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            try (FileChannel directory = FileChannel.open(root, StandardOpenOption.READ)) {
                directory.force(true);
            }
        } catch (IOException failure) {
            indeterminate = true;
            throw failure;
        }
        entries.put(commandId, entry);
        if (!(message instanceof AgentMessage.StartSession)) {
            highWater.put(sessionId, next);
        }
        return entry;
    }

    synchronized Entry find(CommandId commandId) throws IOException {
        requireOpen();
        return entries.get(commandId);
    }

    synchronized boolean hasSession(SessionId sessionId) throws IOException {
        requireOpen();
        for (Entry entry : entries.values()) {
            if (entry.sessionId().equals(sessionId)) {
                return true;
            }
        }
        return false;
    }

    private byte[] encode(Entry entry) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeUTF(entry.agentId().value());
            output.writeInt(entry.encoded().length);
            output.write(entry.encoded());
        }
        if (bytes.size() > MAX_RECORD_BYTES) {
            throw new IOException("Command record exceeds size limit");
        }
        return bytes.toByteArray();
    }

    private Entry decode(byte[] bytes) throws IOException {
        if (bytes.length > MAX_RECORD_BYTES) {
            throw new IOException("Command record exceeds size limit");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                throw new IOException("Command record header is invalid");
            }
            AgentId agentId = new AgentId(input.readUTF());
            int size = input.readInt();
            if (size <= 0 || size > AgentProtocolLimits.HARD_MAX_MESSAGE_BYTES) {
                throw new IOException("Command envelope size is invalid");
            }
            byte[] encoded = input.readNBytes(size);
            if (encoded.length != size || input.available() != 0) {
                throw new IOException("Command record is incomplete");
            }
            return new Entry(agentId, codec.decode(encoded), encoded);
        } catch (AgentProtocolException | IllegalArgumentException failure) {
            throw new IOException("Command record contains an invalid envelope", failure);
        }
    }

    private static String fileName(CommandId commandId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    commandId.value().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest) + ".command";
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static CommandId commandId(AgentMessage message) {
        return switch (message) {
            case AgentMessage.StartSession value -> value.commandId();
            case AgentMessage.Input value -> value.commandId();
            case AgentMessage.Resize value -> value.commandId();
            case AgentMessage.Signal value -> value.commandId();
            case AgentMessage.Terminate value -> value.commandId();
            default -> throw new IllegalArgumentException("Unsupported session command");
        };
    }

    private static SessionId sessionId(AgentMessage message) {
        return switch (message) {
            case AgentMessage.StartSession value -> value.sessionId();
            case AgentMessage.Input value -> value.sessionId();
            case AgentMessage.Resize value -> value.sessionId();
            case AgentMessage.Signal value -> value.sessionId();
            case AgentMessage.Terminate value -> value.sessionId();
            default -> throw new IllegalArgumentException("Unsupported session command");
        };
    }

    static long sequence(AgentMessage message) {
        return switch (message) {
            case AgentMessage.Input value -> value.operationSequence();
            case AgentMessage.Resize value -> value.operationSequence();
            case AgentMessage.Signal value -> value.operationSequence();
            case AgentMessage.Terminate value -> value.operationSequence();
            default -> 0;
        };
    }

    private void requireOpen() throws IOException {
        if (closed || indeterminate) {
            throw new IOException("Command ledger is closed or requires recovery");
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            try {
                lock.release();
            } finally {
                lockChannel.close();
            }
        }
    }

    record Entry(AgentId agentId, AgentMessage message, byte[] encoded) {
        Entry {
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(message, "message");
            encoded = Objects.requireNonNull(encoded, "encoded").clone();
            FileSystemCommandLedger.commandId(message);
        }

        CommandId commandId() {
            return FileSystemCommandLedger.commandId(message);
        }

        SessionId sessionId() {
            return FileSystemCommandLedger.sessionId(message);
        }
    }
}
