package pro.deta.orion.git.nativestorage.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import pro.deta.orion.git.nativestorage.GitObjectId;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class LocalPackPublicationStore implements PackPublicationStore {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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

    @Override
    public List<PublishedPackManifest> publishedPacks() {
        List<PublishedPackManifest> manifests = new ArrayList<>();
        if (!Files.isDirectory(packsDirectory)) {
            return List.of();
        }
        for (Path manifest : publishedManifestPaths()) {
            readManifest(manifest).ifPresent(manifests::add);
        }
        return List.copyOf(manifests);
    }

    @Override
    public Optional<PublishedPackContent> openPublishedPack(
            String packId) {
        if (!isLowercaseSha1(packId)) {
            return Optional.empty();
        }
        Path manifestPath = packsDirectory.resolve(packId + ".json");
        Optional<PublishedPackManifest> manifest = readManifest(manifestPath);
        if (manifest.isEmpty() || !packId.equals(manifest.get().packId())) {
            return Optional.empty();
        }
        Path packPath = packsDirectory.resolve(packId + ".pack");
        Path indexPath = packsDirectory.resolve(packId + ".idx");
        if (!Files.isRegularFile(packPath)
                || !Files.isRegularFile(indexPath)) {
            return Optional.empty();
        }
        try {
            InputStream input = Files.newInputStream(packPath);
            return Optional.of(new PublishedPackContent(
                    manifest.get(),
                    input));
        } catch (IOException error) {
            throw new UncheckedIOException(
                    "Failed to open published native Git pack",
                    error);
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
        appendBoolean(
                json,
                "selfContained",
                request.externalBaseIds().isEmpty(),
                true);
        json.append("  \"objectIds\": [");
        for (int index = 0; index < request.objectIds().size(); index++) {
            if (index > 0) {
                json.append(", ");
            }
            GitObjectId objectId = request.objectIds().get(index);
            json.append('"').append(objectId.value()).append('"');
        }
        json.append("],\n");
        json.append("  \"externalBaseIds\": [");
        List<GitObjectId> externalBaseIds =
                new ArrayList<>(request.externalBaseIds());
        externalBaseIds.sort(Comparator.comparing(GitObjectId::value));
        for (int index = 0; index < externalBaseIds.size(); index++) {
            if (index > 0) {
                json.append(", ");
            }
            GitObjectId objectId = externalBaseIds.get(index);
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

    private static void appendBoolean(
            StringBuilder json,
            String name,
            boolean value,
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

    private List<Path> publishedManifestPaths() {
        List<Path> manifests = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(packsDirectory, "*.json")) {
            for (Path path : stream) {
                if (Files.isRegularFile(path)) {
                    manifests.add(path);
                }
            }
        } catch (IOException error) {
            throw new UncheckedIOException(
                    "Failed to list native Git pack manifests",
                    error);
        }
        manifests.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return manifests;
    }

    private static Optional<PublishedPackManifest> readManifest(
            Path manifestPath) {
        if (!Files.isRegularFile(manifestPath)) {
            return Optional.empty();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(manifestPath.toFile());
            String packId = text(root, "packId");
            String packChecksum = text(root, "packChecksum");
            String indexChecksum = text(root, "indexChecksum");
            long packBytes = root.path("packBytes").asLong(-1);
            int objectCount = root.path("objectCount").asInt(-1);
            Set<GitObjectId> objectIds = objectIds(root.path("objectIds"));
            Set<GitObjectId> externalBaseIds =
                    objectIds(root.path("externalBaseIds"));
            boolean selfContained = root.has("selfContained")
                    && root.path("selfContained").asBoolean(false);
            return Optional.of(new PublishedPackManifest(
                    packId,
                    packBytes,
                    objectCount,
                    packChecksum,
                    indexChecksum,
                    selfContained,
                    objectIds,
                    externalBaseIds));
        } catch (IOException error) {
            throw new UncheckedIOException(
                    "Failed to read native Git pack manifest",
                    error);
        }
    }

    private static String text(JsonNode root, String name) {
        JsonNode value = root.path(name);
        if (!value.isTextual()) {
            throw new IllegalStateException(
                    "Native Git pack manifest is missing " + name);
        }
        return value.asText();
    }

    private static Set<GitObjectId> objectIds(JsonNode node) {
        if (!node.isArray()) {
            return Set.of();
        }
        Set<GitObjectId> objectIds = new LinkedHashSet<>();
        for (JsonNode objectId : node) {
            if (!objectId.isTextual()) {
                throw new IllegalStateException(
                        "Native Git pack manifest object id is not text");
            }
            objectIds.add(GitObjectId.of(objectId.asText()));
        }
        return objectIds;
    }

    private static boolean isLowercaseSha1(String value) {
        if (value == null || value.length() != 40) {
            return false;
        }
        try {
            byte[] ignored = HexFormat.of().parseHex(value);
            return value.equals(value.toLowerCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
