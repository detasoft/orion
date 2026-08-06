package pro.deta.orion.git.nativestorage.pack;

import pro.deta.orion.git.common.GitObjectId;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class LocalPackPublicationStore implements PackPublicationStore {
    private final Path packsDirectory;
    private final Path transactionsDirectory;

    public LocalPackPublicationStore(Path repositoryDirectory) {
        Path root = Objects.requireNonNull(
                repositoryDirectory,
                "repositoryDirectory").toAbsolutePath().normalize();
        packsDirectory = root.resolve("packs");
        transactionsDirectory = root.resolve("tmp").resolve("pack-publication");
        createDirectories(packsDirectory);
        createDirectories(transactionsDirectory);
    }

    @Override
    public Optional<PublishedPack> publish(PackPublicationRequest request) {
        Objects.requireNonNull(request, "request");
        Path transactionDirectory = transactionsDirectory.resolve(
                request.packId() + "-" + UUID.randomUUID());
        Path stagedPack = transactionDirectory.resolve("incoming.pack");
        Path stagedIndex = transactionDirectory.resolve("incoming.idx");
        Path stagedManifest = transactionDirectory.resolve("manifest.json");
        Path publishedPack = packsDirectory.resolve(request.packId() + ".pack");
        Path publishedIndex = packsDirectory.resolve(request.packId() + ".idx");
        Path publishedManifest = packsDirectory.resolve(request.packId() + ".json");
        try {
            createDirectories(transactionDirectory);
            Files.write(stagedPack, request.packBytes());
            Files.write(stagedIndex, request.indexBytes());
            Files.writeString(
                    stagedManifest,
                    manifestJson(request),
                    StandardCharsets.UTF_8);
            moveAtomicallyIfSupported(stagedPack, publishedPack);
            moveAtomicallyIfSupported(stagedIndex, publishedIndex);
            moveAtomicallyIfSupported(stagedManifest, publishedManifest);
            deleteIfExists(transactionDirectory);
            return Optional.of(new PublishedPack(
                    request.packId(),
                    request.packBytes().length,
                    request.objectCount(),
                    request.packId(),
                    request.indexId()));
        } catch (IOException error) {
            cleanup(transactionDirectory, error);
            throw new UncheckedIOException("Failed to publish native Git pack", error);
        }
    }

    private static String manifestJson(PackPublicationRequest request) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        appendString(json, "packId", request.packId(), true);
        appendString(json, "packChecksum", request.packId(), true);
        appendString(json, "indexChecksum", request.indexId(), true);
        appendNumber(json, "packBytes", request.packBytes().length, true);
        appendNumber(json, "objectCount", request.objectCount(), true);
        appendString(json, "visibility", "PUBLISHED", true);
        appendString(json, "source", "receive-pack", true);
        json.append("  \"objectIds\": [");
        for (int index = 0; index < request.objectIds().size(); index++) {
            if (index > 0) {
                json.append(", ");
            }
            GitObjectId objectId = request.objectIds().get(index);
            json.append('"').append(objectId.value()).append('"');
        }
        json.append("]\n");
        json.append("}\n");
        return json.toString();
    }

    private static void appendString(
            StringBuilder json,
            String name,
            String value,
            boolean comma) {
        json.append("  \"")
                .append(name)
                .append("\": \"")
                .append(value)
                .append('"');
        if (comma) {
            json.append(',');
        }
        json.append('\n');
    }

    private static void appendNumber(
            StringBuilder json,
            String name,
            long value,
            boolean comma) {
        json.append("  \"")
                .append(name)
                .append("\": ")
                .append(value);
        if (comma) {
            json.append(',');
        }
        json.append('\n');
    }

    private static void cleanup(Path transactionDirectory, IOException error) {
        try {
            deleteIfExists(transactionDirectory);
        } catch (IOException cleanupError) {
            error.addSuppressed(cleanupError);
        }
    }

    private static void deleteIfExists(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var entries = Files.list(path)) {
            for (Path entry : entries.toList()) {
                if (Files.isDirectory(entry)) {
                    deleteIfExists(entry);
                } else {
                    Files.deleteIfExists(entry);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    private static void createDirectories(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to create directory: " + path, error);
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
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
