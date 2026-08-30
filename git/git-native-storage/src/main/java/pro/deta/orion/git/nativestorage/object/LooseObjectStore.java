package pro.deta.orion.git.nativestorage.object;

import pro.deta.orion.git.nativestorage.GitObjectId;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public final class LooseObjectStore {
    private static final int MAX_OBJECT_HEADER_BYTES = 64;

    private final ConcurrentHashMap<String, byte[]> store = new ConcurrentHashMap<>();
    private final Path directory;

    public LooseObjectStore() {
        this.directory = null;
    }

    public LooseObjectStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        createDirectories(this.directory);
    }

    public GitObjectId write(ObjectType type, byte[] data) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(data, "data");
        byte[] header = objectHeader(type, data.length);
        byte[] raw = concat(header, data);
        String id = sha1Hex(raw);
        putCompressed(id, deflate(raw));
        return GitObjectId.of(id);
    }

    public Optional<LooseObject> read(GitObjectId id) {
        Objects.requireNonNull(id, "id");
        byte[] compressed = readCompressed(id.value());
        if (compressed == null) {
            return Optional.empty();
        }
        byte[] raw = inflate(compressed);
        return Optional.of(parseRaw(id, raw));
    }

    public Optional<LooseObjectPrefix> readPrefix(GitObjectId id, int maxDataBytes) {
        Objects.requireNonNull(id, "id");
        if (maxDataBytes < 0) {
            throw new IllegalArgumentException("maxDataBytes must be nonnegative");
        }
        byte[] compressed = readCompressed(id.value());
        if (compressed == null) {
            return Optional.empty();
        }
        try (InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            ParsedHeader header = readHeader(inflater);
            int prefixLength = (int) Math.min(header.declaredDataLength(), maxDataBytes);
            byte[] prefix = readExactly(inflater, prefixLength);
            return Optional.of(new LooseObjectPrefix(id, header.type(), header.declaredDataLength(), prefix));
        } catch (IOException e) {
            throw new UncheckedIOException("Malformed compressed loose object", e);
        }
    }

    public boolean contains(GitObjectId id) {
        Objects.requireNonNull(id, "id");
        if (store.containsKey(id.value())) {
            return true;
        }
        return objectPath(id.value()).filter(Files::isRegularFile).isPresent();
    }

    public void putAll(LooseObjectStore other) {
        Objects.requireNonNull(other, "other");
        other.forEachCompressed(this::putCompressed);
    }

    private byte[] readCompressed(String id) {
        byte[] cached = store.get(id);
        if (cached != null) {
            return cached.clone();
        }
        Optional<Path> path = objectPath(id);
        if (path.isEmpty() || !Files.isRegularFile(path.get())) {
            return null;
        }
        try {
            byte[] compressed = Files.readAllBytes(path.get());
            store.putIfAbsent(id, compressed);
            return compressed;
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to read loose Git object", error);
        }
    }

    private void putCompressed(String id, byte[] compressed) {
        store.put(id, compressed);
        objectPath(id).ifPresent(path -> writeCompressed(path, compressed));
    }

    private void writeCompressed(Path path, byte[] compressed) {
        createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + Thread.currentThread().getId() + "-" + System.nanoTime());
        try {
            Files.write(temporary, compressed);
            moveAtomicallyIfSupported(temporary, path);
        } catch (IOException error) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw new UncheckedIOException("Failed to write loose Git object", error);
        }
    }

    private void forEachCompressed(BiConsumer<String, byte[]> consumer) {
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, byte[]> entry : store.entrySet()) {
            if (seen.add(entry.getKey())) {
                consumer.accept(entry.getKey(), entry.getValue().clone());
            }
        }
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        try (DirectoryStream<Path> buckets = Files.newDirectoryStream(directory)) {
            for (Path bucket : buckets) {
                if (!Files.isDirectory(bucket)) {
                    continue;
                }
                String prefix = bucket.getFileName().toString();
                if (prefix.length() != 2) {
                    continue;
                }
                try (DirectoryStream<Path> objects = Files.newDirectoryStream(bucket)) {
                    for (Path object : objects) {
                        if (!Files.isRegularFile(object)) {
                            continue;
                        }
                        String id = prefix + object.getFileName();
                        if (!isObjectId(id) || !seen.add(id)) {
                            continue;
                        }
                        consumer.accept(id, Files.readAllBytes(object));
                    }
                }
            }
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to list loose Git objects", error);
        }
    }

    private Optional<Path> objectPath(String id) {
        if (directory == null || !isObjectId(id)) {
            return Optional.empty();
        }
        return Optional.of(directory.resolve(id.substring(0, 2)).resolve(id.substring(2)).normalize());
    }

    private static boolean isObjectId(String id) {
        if (id == null || id.length() != 40) {
            return false;
        }
        for (int index = 0; index < id.length(); index++) {
            char character = id.charAt(index);
            boolean hex = character >= '0' && character <= '9' || character >= 'a' && character <= 'f';
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private static void createDirectories(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to create directory: " + path, error);
        }
    }

    private static void moveAtomicallyIfSupported(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static LooseObject parseRaw(GitObjectId id, byte[] raw) {
        int nul = indexOf(raw, (byte) 0);
        if (nul < 0) {
            throw new ObjectFormatException("Loose object missing header NUL separator");
        }
        String header = new String(raw, 0, nul, StandardCharsets.US_ASCII);
        int space = header.indexOf(' ');
        if (space < 0) {
            throw new ObjectFormatException("Loose object header missing space");
        }
        String typeName = header.substring(0, space);
        ObjectType type = parseTypeName(typeName);
        byte[] data = new byte[raw.length - nul - 1];
        System.arraycopy(raw, nul + 1, data, 0, data.length);
        return new LooseObject(id, type, data);
    }

    private static ObjectType parseTypeName(String name) {
        for (ObjectType type : ObjectType.values()) {
            if (type.headerName().equals(name)) {
                return type;
            }
        }
        throw new ObjectFormatException("Unknown object type: " + name);
    }

    private static ParsedHeader readHeader(InflaterInputStream inflater) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        while (header.size() < MAX_OBJECT_HEADER_BYTES) {
            int value = inflater.read();
            if (value < 0) {
                throw new ObjectFormatException("Loose object header is truncated");
            }
            if (value == 0) {
                return parseHeader(header.toByteArray());
            }
            header.write(value);
        }
        throw new ObjectFormatException("Loose object header exceeds maximum length");
    }

    private static ParsedHeader parseHeader(byte[] headerBytes) {
        String header = new String(headerBytes, StandardCharsets.US_ASCII);
        int space = header.indexOf(' ');
        if (space <= 0 || space == header.length() - 1) {
            throw new ObjectFormatException("Malformed loose object header");
        }
        String lengthText = header.substring(space + 1);
        for (int i = 0; i < lengthText.length(); i++) {
            char character = lengthText.charAt(i);
            if (character < '0' || character > '9') {
                throw new ObjectFormatException("Malformed loose object data length");
            }
        }
        long declaredDataLength;
        try {
            declaredDataLength = Long.parseLong(lengthText);
        } catch (NumberFormatException e) {
            throw new ObjectFormatException("Loose object data length is too large");
        }
        return new ParsedHeader(parseTypeName(header.substring(0, space)), declaredDataLength);
    }

    private static byte[] readExactly(InflaterInputStream inflater, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = inflater.read(data, offset, length - offset);
            if (read < 0) {
                throw new ObjectFormatException("Loose object data prefix is truncated");
            }
            offset += read;
        }
        return data;
    }

    private static byte[] objectHeader(ObjectType type, int dataLength) {
        return (type.headerName() + " " + dataLength + "\0").getBytes(StandardCharsets.US_ASCII);
    }

    private static String sha1Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }

    private static byte[] deflate(byte[] data) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (DeflaterOutputStream deflater = new DeflaterOutputStream(out)) {
                deflater.write(data);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] inflate(byte[] compressed) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = inflater.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                }
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private static int indexOf(byte[] array, byte target) {
        for (int i = 0; i < array.length; i++) {
            if (array[i] == target) {
                return i;
            }
        }
        return -1;
    }

    private record ParsedHeader(ObjectType type, long declaredDataLength) {
    }
}
