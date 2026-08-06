package pro.deta.orion.git.nativestorage.pack;

import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.object.LooseObject;
import pro.deta.orion.git.nativestorage.object.ObjectFormatException;
import pro.deta.orion.git.nativestorage.object.ObjectType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public final class LocalPackObjectDirectory implements PackObjectDirectory {
    private static final int PACK_INDEX_MAGIC = 0xff744f63;
    private static final int PACK_INDEX_VERSION = 2;
    private static final int FANOUT_ENTRIES = 256;
    private static final int SHA1_BYTES = 20;
    private static final int PACK_HEADER_BYTES = 12;
    private static final int LARGE_OFFSET_FLAG = 0x80000000;
    private static final int PACK_OFS_DELTA_TYPE = 6;
    private static final int PACK_REF_DELTA_TYPE = 7;
    private static final int INFLATE_CHUNK_BYTES = 8192;

    private final Path packsDirectory;

    public LocalPackObjectDirectory(Path repositoryDirectory) {
        Path root = Objects.requireNonNull(
                repositoryDirectory,
                "repositoryDirectory").toAbsolutePath().normalize();
        packsDirectory = root.resolve("packs");
    }

    @Override
    public Optional<LooseObject> read(GitObjectId id) {
        Objects.requireNonNull(id, "id");
        if (!Files.isDirectory(packsDirectory)) {
            return Optional.empty();
        }
        for (Path manifest : publishedManifests()) {
            Optional<LooseObject> object = readFromPublishedPack(id, manifest);
            if (object.isPresent()) {
                return object;
            }
        }
        return Optional.empty();
    }

    private Optional<LooseObject> readFromPublishedPack(
            GitObjectId id,
            Path manifest) {
        String packId = packId(manifest);
        Path packPath = packsDirectory.resolve(packId + ".pack");
        Path indexPath = packsDirectory.resolve(packId + ".idx");
        if (!Files.isRegularFile(packPath) || !Files.isRegularFile(indexPath)) {
            return Optional.empty();
        }
        try {
            byte[] indexBytes = Files.readAllBytes(indexPath);
            PackIndex index = PackIndex.parse(indexBytes);
            OptionalLong objectOffset = index.findOffset(id);
            if (objectOffset.isEmpty()) {
                return Optional.empty();
            }
            byte[] packBytes = Files.readAllBytes(packPath);
            verifyPackChecksum(packPath, packBytes, index.packChecksum());
            return readPackObject(id, packBytes, objectOffset.getAsLong());
        } catch (IOException error) {
            throw new UncheckedIOException(
                    "Failed to read native Git pack object",
                    error);
        }
    }

    private List<Path> publishedManifests() {
        List<Path> manifests = new ArrayList<>();
        try (DirectoryStream<Path> stream =
                     Files.newDirectoryStream(packsDirectory, "*.json")) {
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

    private static String packId(Path manifest) {
        String fileName = manifest.getFileName().toString();
        return fileName.substring(0, fileName.length() - ".json".length());
    }

    private static Optional<LooseObject> readPackObject(
            GitObjectId expectedId,
            byte[] pack,
            long offset) {
        if (offset < PACK_HEADER_BYTES || offset >= pack.length - SHA1_BYTES) {
            throw new ObjectFormatException("Pack object offset is outside pack data");
        }
        if (offset > Integer.MAX_VALUE) {
            throw new ObjectFormatException("Pack object offset exceeds local reader limit");
        }
        int position = (int) offset;
        PackObjectHeader header = readObjectHeader(pack, position);
        if (header.typeId() == PACK_OFS_DELTA_TYPE
                || header.typeId() == PACK_REF_DELTA_TYPE) {
            return Optional.empty();
        }
        ObjectType type = ObjectType.fromPackTypeId(header.typeId());
        byte[] data = inflateObject(pack, header.dataOffset(), header.declaredSize());
        String actualId = objectId(type, data);
        if (!actualId.equals(expectedId.value())) {
            throw new ObjectFormatException("Pack object id does not match index entry");
        }
        return Optional.of(new LooseObject(expectedId, type, data));
    }

    private static PackObjectHeader readObjectHeader(
            byte[] pack,
            int offset) {
        int position = offset;
        int first = readPackDataByte(pack, position++);
        int typeId = (first >>> 4) & 0x07;
        long size = first & 0x0fL;
        int shift = 4;
        int current = first;
        while ((current & 0x80) != 0) {
            current = readPackDataByte(pack, position++);
            if (shift > 60) {
                throw new ObjectFormatException("Pack object size is too large");
            }
            size |= (long) (current & 0x7f) << shift;
            shift += 7;
        }
        if (size > Integer.MAX_VALUE) {
            throw new ObjectFormatException("Pack object exceeds local reader limit");
        }
        return new PackObjectHeader(typeId, size, position);
    }

    private static int readPackDataByte(byte[] pack, int offset) {
        if (offset < PACK_HEADER_BYTES || offset >= pack.length - SHA1_BYTES) {
            throw new ObjectFormatException("Pack object header is truncated");
        }
        return pack[offset] & 0xff;
    }

    private static byte[] inflateObject(
            byte[] pack,
            int offset,
            long declaredSize) {
        if (offset >= pack.length - SHA1_BYTES) {
            throw new ObjectFormatException("Pack object data is truncated");
        }
        Inflater inflater = new Inflater();
        inflater.setInput(pack, offset, pack.length - SHA1_BYTES - offset);
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) declaredSize);
        byte[] chunk = new byte[INFLATE_CHUNK_BYTES];
        try {
            while (!inflater.finished()) {
                int produced = inflater.inflate(chunk);
                if (produced > 0) {
                    if (output.size() + produced > declaredSize) {
                        throw new ObjectFormatException(
                                "Inflated pack object exceeds declared size");
                    }
                    output.write(chunk, 0, produced);
                    continue;
                }
                if (inflater.needsDictionary()) {
                    throw new ObjectFormatException(
                            "Pack deflate stream requires a dictionary");
                }
                if (inflater.needsInput()) {
                    throw new ObjectFormatException("Pack object data is truncated");
                }
                throw new ObjectFormatException("Invalid stalled pack deflate stream");
            }
        } catch (DataFormatException error) {
            throw new ObjectFormatException(
                    "Invalid deflate stream in pack: " + error.getMessage());
        } finally {
            inflater.end();
        }
        byte[] data = output.toByteArray();
        if (data.length != declaredSize) {
            throw new ObjectFormatException(
                    "Inflated pack object size does not match declared size");
        }
        return data;
    }

    private static void verifyPackChecksum(
            Path packPath,
            byte[] pack,
            byte[] expectedChecksum) {
        if (pack.length < PACK_HEADER_BYTES + SHA1_BYTES) {
            throw new ObjectFormatException(
                    "Published pack is too short: " + packPath);
        }
        byte[] actualChecksum =
                Arrays.copyOfRange(pack, pack.length - SHA1_BYTES, pack.length);
        if (!Arrays.equals(actualChecksum, expectedChecksum)) {
            throw new ObjectFormatException(
                    "Pack checksum does not match index: " + packPath);
        }
    }

    private static String objectId(ObjectType type, byte[] data) {
        MessageDigest digest = sha1Digest();
        byte[] header = (type.headerName() + " " + data.length + "\0")
                .getBytes(StandardCharsets.US_ASCII);
        digest.update(header);
        digest.update(data);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha1Digest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-1 not available", error);
        }
    }

    private record PackObjectHeader(
            int typeId,
            long declaredSize,
            int dataOffset) {
    }

    private record PackIndex(
            byte[] bytes,
            int[] fanout,
            int objectCount,
            int namesOffset,
            int offsetTableOffset,
            int largeOffsetTableOffset,
            byte[] packChecksum) {
        private PackIndex {
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
            fanout = Objects.requireNonNull(fanout, "fanout").clone();
            packChecksum = Objects.requireNonNull(
                    packChecksum,
                    "packChecksum").clone();
        }

        private static PackIndex parse(byte[] index) {
            Objects.requireNonNull(index, "index");
            int minimumLength = 8 + FANOUT_ENTRIES * Integer.BYTES + SHA1_BYTES * 2;
            if (index.length < minimumLength) {
                throw new ObjectFormatException("Pack index is truncated");
            }
            if (intAt(index, 0) != PACK_INDEX_MAGIC) {
                throw new ObjectFormatException("Unsupported pack index magic");
            }
            if (intAt(index, 4) != PACK_INDEX_VERSION) {
                throw new ObjectFormatException("Unsupported pack index version");
            }
            verifyIndexChecksum(index);
            int[] fanout = readFanout(index);
            int objectCount = fanout[FANOUT_ENTRIES - 1];
            int namesOffset = 8 + FANOUT_ENTRIES * Integer.BYTES;
            long crcOffset = namesOffset + objectCount * (long) SHA1_BYTES;
            long offsetTableOffset = crcOffset + objectCount * (long) Integer.BYTES;
            long largeOffsetTableOffset =
                    offsetTableOffset + objectCount * (long) Integer.BYTES;
            long packChecksumOffset = index.length - SHA1_BYTES * 2L;
            if (largeOffsetTableOffset > packChecksumOffset
                    || (packChecksumOffset - largeOffsetTableOffset) % Long.BYTES != 0) {
                throw new ObjectFormatException("Pack index table lengths are invalid");
            }
            if (largeOffsetTableOffset > Integer.MAX_VALUE) {
                throw new ObjectFormatException("Pack index exceeds local reader limit");
            }
            byte[] packChecksum = Arrays.copyOfRange(
                    index,
                    (int) packChecksumOffset,
                    (int) packChecksumOffset + SHA1_BYTES);
            return new PackIndex(
                    index,
                    fanout,
                    objectCount,
                    namesOffset,
                    (int) offsetTableOffset,
                    (int) largeOffsetTableOffset,
                    packChecksum);
        }

        private OptionalLong findOffset(GitObjectId id) {
            if (!isSha1Hex(id.value())) {
                return OptionalLong.empty();
            }
            byte[] objectId = HexFormat.of().parseHex(id.value());
            int bucket = objectId[0] & 0xff;
            int low = bucket == 0 ? 0 : fanout[bucket - 1];
            int high = fanout[bucket] - 1;
            while (low <= high) {
                int middle = (low + high) >>> 1;
                int comparison = compareObjectId(middle, objectId);
                if (comparison < 0) {
                    low = middle + 1;
                } else if (comparison > 0) {
                    high = middle - 1;
                } else {
                    return OptionalLong.of(offsetAt(middle));
                }
            }
            return OptionalLong.empty();
        }

        private static boolean isSha1Hex(String value) {
            if (value == null || value.length() != SHA1_BYTES * 2) {
                return false;
            }
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                boolean hex = character >= '0' && character <= '9'
                        || character >= 'a' && character <= 'f';
                if (!hex) {
                    return false;
                }
            }
            return true;
        }

        private int compareObjectId(
                int objectIndex,
                byte[] objectId) {
            int offset = namesOffset + objectIndex * SHA1_BYTES;
            for (int index = 0; index < SHA1_BYTES; index++) {
                int current = bytes[offset + index] & 0xff;
                int expected = objectId[index] & 0xff;
                if (current != expected) {
                    return Integer.compare(current, expected);
                }
            }
            return 0;
        }

        private long offsetAt(int objectIndex) {
            int tableValue = intAt(
                    bytes,
                    offsetTableOffset + objectIndex * Integer.BYTES);
            if ((tableValue & LARGE_OFFSET_FLAG) == 0) {
                return tableValue & 0xffffffffL;
            }
            int largeOffsetIndex = tableValue & ~LARGE_OFFSET_FLAG;
            int largeOffsetOffset = largeOffsetTableOffset
                    + largeOffsetIndex * Long.BYTES;
            if (largeOffsetOffset + Long.BYTES > bytes.length - SHA1_BYTES * 2) {
                throw new ObjectFormatException(
                        "Pack index large offset is outside large-offset table");
            }
            return longAt(bytes, largeOffsetOffset);
        }

        @Override
        public byte[] packChecksum() {
            return packChecksum.clone();
        }

        private static int[] readFanout(byte[] index) {
            int[] fanout = new int[FANOUT_ENTRIES];
            int previous = 0;
            for (int entry = 0; entry < FANOUT_ENTRIES; entry++) {
                int count = intAt(index, 8 + entry * Integer.BYTES);
                if (count < previous) {
                    throw new ObjectFormatException(
                            "Pack index fanout table decreases");
                }
                fanout[entry] = count;
                previous = count;
            }
            return fanout;
        }

        private static void verifyIndexChecksum(byte[] index) {
            byte[] expected =
                    Arrays.copyOfRange(index, index.length - SHA1_BYTES, index.length);
            byte[] actual = sha1Digest().digest(
                    Arrays.copyOf(index, index.length - SHA1_BYTES));
            if (!Arrays.equals(actual, expected)) {
                throw new ObjectFormatException("Pack index checksum mismatch");
            }
        }

        private static int intAt(byte[] data, int offset) {
            return ((data[offset] & 0xff) << 24)
                    | ((data[offset + 1] & 0xff) << 16)
                    | ((data[offset + 2] & 0xff) << 8)
                    | (data[offset + 3] & 0xff);
        }

        private static long longAt(byte[] data, int offset) {
            return ((data[offset] & 0xffL) << 56)
                    | ((data[offset + 1] & 0xffL) << 48)
                    | ((data[offset + 2] & 0xffL) << 40)
                    | ((data[offset + 3] & 0xffL) << 32)
                    | ((data[offset + 4] & 0xffL) << 24)
                    | ((data[offset + 5] & 0xffL) << 16)
                    | ((data[offset + 6] & 0xffL) << 8)
                    | (data[offset + 7] & 0xffL);
        }
    }
}
