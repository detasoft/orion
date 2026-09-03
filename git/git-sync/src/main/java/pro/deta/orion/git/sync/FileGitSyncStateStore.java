package pro.deta.orion.git.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

public final class FileGitSyncStateStore extends GitSyncStateStore {
    private static final int FORMAT_VERSION = 1;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Path directory;
    private final DirectorySync directorySync;
    private final Map<StateKey, GitSyncSnapshot> loaded = new HashMap<>();

    public FileGitSyncStateStore(Path directory) {
        this(directory, FileGitSyncStateStore::forceDirectory);
    }

    FileGitSyncStateStore(
            Path directory,
            DirectorySync directorySync) {
        this.directory = Objects.requireNonNull(directory, "directory")
                .toAbsolutePath()
                .normalize();
        this.directorySync = Objects.requireNonNull(
                directorySync,
                "directorySync");
        try {
            Files.createDirectories(this.directory);
        } catch (IOException error) {
            throw new UncheckedIOException(
                    "Failed to create Git synchronization state directory",
                    error);
        }
    }

    @Override
    protected synchronized GitSyncSnapshot readSnapshot(StateKey key) {
        GitSyncSnapshot existing = loaded.get(key);
        if (existing != null) {
            return existing;
        }
        GitSyncSnapshot snapshot = readFile(statePath(key))
                .pendingAfterRestart();
        loaded.put(key, snapshot);
        return snapshot;
    }

    @Override
    protected synchronized GitSyncSnapshot updateSnapshot(
            StateKey key,
            UnaryOperator<GitSyncSnapshot> update) {
        GitSyncSnapshot updated = update.apply(readSnapshot(key));
        writeFile(statePath(key), updated);
        loaded.put(key, updated);
        return updated;
    }

    private GitSyncSnapshot readFile(Path path) {
        if (!Files.isRegularFile(path)) {
            return GitSyncSnapshot.attaching();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(path.toFile());
            if (root.path("version").asInt(-1) != FORMAT_VERSION) {
                throw new IllegalStateException(
                        "Unsupported Git synchronization state version");
            }
            return new GitSyncSnapshot(
                    enumValue(root, "state", GitSyncState.class),
                    instant(root.get("lastAttemptEpochMillis")),
                    failure(root.get("lastFailure")),
                    conflicts(root.path("conflicts")),
                    outboundWork(root.path("outboundWork")),
                    positiveLong(root, "nextSequence"));
        } catch (IOException error) {
            throw new UncheckedIOException(
                    "Failed to read Git synchronization state",
                    error);
        }
    }

    private void writeFile(Path path, GitSyncSnapshot snapshot) {
        Path temporary = path.resolveSibling(
                path.getFileName() + ".tmp-" + System.nanoTime());
        try {
            byte[] json = OBJECT_MAPPER.writeValueAsBytes(json(snapshot));
            try (FileChannel output = FileChannel.open(
                    temporary,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(json);
                while (bytes.hasRemaining()) {
                    output.write(bytes);
                }
                output.force(true);
            }
            Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            directorySync.force(directory);
        } catch (AtomicMoveNotSupportedException error) {
            cleanup(temporary, error);
            throw new UncheckedIOException(
                    "Git synchronization state requires atomic replacement",
                    error);
        } catch (IOException error) {
            cleanup(temporary, error);
            throw new UncheckedIOException(
                    "Failed to persist Git synchronization state",
                    error);
        }
    }

    private Path statePath(StateKey key) {
        byte[] identity = (key.repositoryId() + "\0" + key.remoteAlias())
                .getBytes(StandardCharsets.UTF_8);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity);
            return directory.resolve(HexFormat.of().formatHex(digest) + ".json");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static ObjectNode json(GitSyncSnapshot snapshot) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("version", FORMAT_VERSION);
        root.put("state", snapshot.state().name());
        snapshot.lastAttemptAt().ifPresentOrElse(
                value -> root.put(
                        "lastAttemptEpochMillis",
                        value.toEpochMilli()),
                () -> root.putNull("lastAttemptEpochMillis"));
        if (snapshot.lastFailure().isPresent()) {
            GitSyncFailure failure = snapshot.lastFailure().orElseThrow();
            ObjectNode failureNode = root.putObject("lastFailure");
            failureNode.put("kind", failure.kind().name());
            failureNode.put("retryable", failure.retryable());
        } else {
            root.putNull("lastFailure");
        }
        ArrayNode conflicts = root.putArray("conflicts");
        for (GitSyncConflict conflict : snapshot.conflicts()) {
            ObjectNode node = conflicts.addObject();
            node.put("refName", conflict.refName());
            putOptional(node, "localObjectId", conflict.localObjectId());
            putOptional(node, "upstreamObjectId", conflict.upstreamObjectId());
            putOptional(node, "mergeBase", conflict.mergeBase());
        }
        ArrayNode work = root.putArray("outboundWork");
        for (GitOutboundWork item : snapshot.outboundWork()) {
            ObjectNode node = work.addObject();
            node.put("refName", item.refName());
            node.put("desiredObjectId", item.desiredObjectId());
            node.put("sequence", item.sequence());
            node.put("attempt", item.attempt());
            node.put("notBeforeEpochMillis", item.notBefore().toEpochMilli());
            node.put("inFlight", item.inFlight());
        }
        root.put("nextSequence", snapshot.nextSequence());
        return root;
    }

    private static Optional<GitSyncFailure> failure(JsonNode node) {
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        return Optional.of(new GitSyncFailure(
                enumValue(node, "kind", GitSyncFailure.Kind.class),
                requiredBoolean(node, "retryable")));
    }

    private static List<GitSyncConflict> conflicts(JsonNode nodes) {
        requireArray(nodes, "conflicts");
        List<GitSyncConflict> conflicts = new ArrayList<>();
        for (JsonNode node : nodes) {
            conflicts.add(new GitSyncConflict(
                    requiredText(node, "refName"),
                    optionalText(node, "localObjectId"),
                    optionalText(node, "upstreamObjectId"),
                    optionalText(node, "mergeBase")));
        }
        return List.copyOf(conflicts);
    }

    private static List<GitOutboundWork> outboundWork(JsonNode nodes) {
        requireArray(nodes, "outboundWork");
        List<GitOutboundWork> work = new ArrayList<>();
        for (JsonNode node : nodes) {
            work.add(new GitOutboundWork(
                    requiredText(node, "refName"),
                    requiredText(node, "desiredObjectId"),
                    positiveLong(node, "sequence"),
                    nonNegativeInt(node, "attempt"),
                    Instant.ofEpochMilli(requiredLong(
                            node,
                            "notBeforeEpochMillis")),
                    requiredBoolean(node, "inFlight")));
        }
        return List.copyOf(work);
    }

    private static Optional<Instant> instant(JsonNode node) {
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        if (!node.canConvertToLong()) {
            throw new IllegalStateException("Invalid last attempt timestamp");
        }
        return Optional.of(Instant.ofEpochMilli(node.longValue()));
    }

    private static Optional<String> optionalText(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isTextual()) {
            throw new IllegalStateException("Invalid Git sync state field: " + name);
        }
        return Optional.of(value.textValue());
    }

    private static void putOptional(
            ObjectNode node,
            String name,
            Optional<String> value) {
        value.ifPresentOrElse(
                item -> node.put(name, item),
                () -> node.putNull(name));
    }

    private static <E extends Enum<E>> E enumValue(
            JsonNode node,
            String name,
            Class<E> type) {
        String value = requiredText(node, name);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException(
                    "Invalid Git sync state field: " + name,
                    error);
        }
    }

    private static String requiredText(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual()) {
            throw new IllegalStateException("Invalid Git sync state field: " + name);
        }
        return value.textValue();
    }

    private static boolean requiredBoolean(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isBoolean()) {
            throw new IllegalStateException("Invalid Git sync state field: " + name);
        }
        return value.booleanValue();
    }

    private static long positiveLong(JsonNode node, String name) {
        long value = requiredLong(node, name);
        if (value < 1) {
            throw new IllegalStateException("Invalid Git sync state field: " + name);
        }
        return value;
    }

    private static int nonNegativeInt(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.canConvertToInt() || value.intValue() < 0) {
            throw new IllegalStateException("Invalid Git sync state field: " + name);
        }
        return value.intValue();
    }

    private static long requiredLong(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.canConvertToLong()) {
            throw new IllegalStateException("Invalid Git sync state field: " + name);
        }
        return value.longValue();
    }

    private static void requireArray(JsonNode node, String name) {
        if (!node.isArray()) {
            throw new IllegalStateException("Invalid Git sync state field: " + name);
        }
    }

    private static void cleanup(Path temporary, IOException error) {
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException cleanupError) {
            error.addSuppressed(cleanupError);
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        if (!Files.getFileStore(directory)
                .supportsFileAttributeView("posix")) {
            return;
        }
        try (FileChannel channel = FileChannel.open(
                directory,
                StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    @FunctionalInterface
    interface DirectorySync {
        void force(Path directory) throws IOException;
    }
}
