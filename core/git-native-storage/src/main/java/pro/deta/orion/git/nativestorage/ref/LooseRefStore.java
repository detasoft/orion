package pro.deta.orion.git.nativestorage.ref;

import pro.deta.orion.git.common.GitObjectId;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class LooseRefStore {
    private static final String NULL_ID = "0".repeat(40);

    private final Map<String, String> refs = new HashMap<>();
    private final Path repositoryDirectory;

    public LooseRefStore() {
        this.repositoryDirectory = null;
    }

    public LooseRefStore(Path repositoryDirectory) {
        this.repositoryDirectory = Objects.requireNonNull(
                repositoryDirectory,
                "repositoryDirectory").toAbsolutePath().normalize();
        createDirectories(this.repositoryDirectory.resolve("refs"));
        loadRefs();
    }

    public synchronized Optional<GitObjectId> read(String refName) {
        Objects.requireNonNull(refName, "refName");
        String value = refs.get(refName);
        return Optional.ofNullable(value).map(GitObjectId::of);
    }

    public synchronized Map<String, String> snapshot() {
        return Map.copyOf(refs);
    }

    public synchronized RefUpdateResult update(String refName, String expectedOldId, String newId) {
        Objects.requireNonNull(refName, "refName");
        Objects.requireNonNull(expectedOldId, "expectedOldId");
        Objects.requireNonNull(newId, "newId");
        Map<String, String> updatedRefs = new HashMap<>(refs);
        RefUpdateResult result = applyUpdate(
                updatedRefs,
                refName,
                expectedOldId,
                newId);
        if (result != RefUpdateResult.STALE
                && result != RefUpdateResult.NO_OP) {
            persistSnapshot(updatedRefs);
            refs.clear();
            refs.putAll(updatedRefs);
        }
        return result;
    }

    public synchronized List<RefUpdateResult> updateAll(List<Update> updates, Runnable beforeUpdates) {
        Objects.requireNonNull(updates, "updates");
        Objects.requireNonNull(beforeUpdates, "beforeUpdates");

        Map<String, String> updatedRefs = new HashMap<>(refs);
        List<RefUpdateResult> results = new ArrayList<>(updates.size());
        boolean anyStale = false;
        boolean anyChanged = false;
        for (Update update : updates) {
            Objects.requireNonNull(update, "update");
            RefUpdateResult result =
                    applyUpdate(updatedRefs, update.refName(), update.expectedOldId(), update.newId());
            results.add(result);
            if (result == RefUpdateResult.STALE) {
                anyStale = true;
            } else if (result != RefUpdateResult.NO_OP) {
                anyChanged = true;
            }
        }

        if (!anyStale && anyChanged) {
            beforeUpdates.run();
            persistSnapshot(updatedRefs);
            refs.clear();
            refs.putAll(updatedRefs);
        }
        return List.copyOf(results);
    }

    private void loadRefs() {
        if (repositoryDirectory == null) {
            return;
        }
        Path refsDirectory = repositoryDirectory.resolve("refs");
        if (!Files.isDirectory(refsDirectory)) {
            return;
        }
        try {
            loadRefs(refsDirectory);
        } catch (IOException error) {
            throw new UncheckedIOException(
                    "Failed to load loose Git refs",
                    error);
        }
    }

    private void loadRefs(Path directory) throws IOException {
        try (DirectoryStream<Path> entries =
                     Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                if (Files.isDirectory(entry)) {
                    loadRefs(entry);
                } else if (Files.isRegularFile(entry)) {
                    if (entry.getFileName().toString().contains(".tmp-")) {
                        continue;
                    }
                    String refName = repositoryDirectory
                            .relativize(entry)
                            .toString()
                            .replace('\\', '/');
                    String objectId = Files.readString(
                            entry,
                            StandardCharsets.US_ASCII).trim();
                    refs.put(refName, objectId);
                }
            }
        }
    }

    private void persistSnapshot(Map<String, String> updatedRefs) {
        if (repositoryDirectory == null) {
            return;
        }
        for (Map.Entry<String, String> entry : updatedRefs.entrySet()) {
            if (!Objects.equals(refs.get(entry.getKey()), entry.getValue())) {
                writeRef(entry.getKey(), entry.getValue());
            }
        }
        for (String existingRef : refs.keySet()) {
            if (!updatedRefs.containsKey(existingRef)) {
                deleteRef(existingRef);
            }
        }
    }

    private void writeRef(String refName, String objectId) {
        Path path = refPath(refName);
        createDirectories(path.getParent());
        Path temporary = path.resolveSibling(
                path.getFileName() + ".tmp-"
                        + Thread.currentThread().getId() + "-"
                        + System.nanoTime());
        try {
            Files.writeString(
                    temporary,
                    objectId + "\n",
                    StandardCharsets.US_ASCII);
            moveAtomicallyIfSupported(temporary, path);
        } catch (IOException error) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw new UncheckedIOException(
                    "Failed to write loose Git ref",
                    error);
        }
    }

    private void deleteRef(String refName) {
        Path path = refPath(refName);
        try {
            Files.deleteIfExists(path);
            pruneEmptyDirectories(path.getParent());
        } catch (IOException error) {
            throw new UncheckedIOException(
                    "Failed to delete loose Git ref",
                    error);
        }
    }

    private void pruneEmptyDirectories(Path directory) throws IOException {
        Path refsDirectory = repositoryDirectory.resolve("refs");
        Path current = directory;
        while (current != null
                && !current.equals(repositoryDirectory)
                && !current.equals(refsDirectory)) {
            try (DirectoryStream<Path> entries =
                         Files.newDirectoryStream(current)) {
                if (entries.iterator().hasNext()) {
                    return;
                }
            }
            Files.deleteIfExists(current);
            current = current.getParent();
        }
    }

    private Path refPath(String refName) {
        if (repositoryDirectory == null) {
            throw new IllegalStateException(
                    "Loose ref store is not file-backed");
        }
        if (!refName.startsWith("refs/")
                || refName.contains("\\")
                || refName.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(
                    "Unsafe Git ref name: " + refName);
        }
        Path relative = Path.of(refName).normalize();
        if (relative.isAbsolute()
                || !relative.toString()
                .replace('\\', '/')
                .equals(refName)) {
            throw new IllegalArgumentException(
                    "Unsafe Git ref name: " + refName);
        }
        Path path = repositoryDirectory.resolve(relative).normalize();
        if (!path.startsWith(repositoryDirectory)) {
            throw new IllegalArgumentException(
                    "Unsafe Git ref name: " + refName);
        }
        return path;
    }

    private static void createDirectories(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException error) {
            throw new UncheckedIOException(
                    "Failed to create directory: " + path,
                    error);
        }
    }

    private static void moveAtomicallyIfSupported(
            Path source,
            Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static RefUpdateResult applyUpdate(
            Map<String, String> targetRefs,
            String refName,
            String expectedOldId,
            String newId) {
        if (NULL_ID.equals(expectedOldId)) {
            if (NULL_ID.equals(newId)) {
                return RefUpdateResult.NO_OP;
            }
            String existing = targetRefs.putIfAbsent(refName, newId);
            if (existing == null) {
                return RefUpdateResult.CREATED;
            }
            if (existing.equals(newId)) {
                return RefUpdateResult.NO_OP;
            }
            return RefUpdateResult.STALE;
        }

        String current = targetRefs.get(refName);
        if (current == null || !current.equals(expectedOldId)) {
            return RefUpdateResult.STALE;
        }
        if (NULL_ID.equals(newId)) {
            targetRefs.remove(refName);
            return RefUpdateResult.DELETED;
        }
        if (current.equals(newId)) {
            return RefUpdateResult.NO_OP;
        }
        targetRefs.put(refName, newId);
        return RefUpdateResult.FAST_FORWARD;
    }

    public record Update(String refName, String expectedOldId, String newId) {
        public Update {
            Objects.requireNonNull(refName, "refName");
            Objects.requireNonNull(expectedOldId, "expectedOldId");
            Objects.requireNonNull(newId, "newId");
        }
    }
}
