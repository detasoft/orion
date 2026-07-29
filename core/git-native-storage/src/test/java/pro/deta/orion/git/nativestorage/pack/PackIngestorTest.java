package pro.deta.orion.git.nativestorage.pack;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackIngestorTest {
    private static final long MAX_BYTES = 10 * 1024 * 1024L;
    private final PackIngestor ingestor = new PackIngestor(MAX_BYTES);

    @Test
    void ingestsValidPackWithSingleBlob() {
        byte[] blobData = "hello world".getBytes();
        byte[] pack = buildPack(new PackObject(ObjectType.BLOB, blobData));

        LooseObjectStore result = ingest(pack);

        assertThat(result).isNotNull();
        assertThat(result.contains(GitObjectId.of(blobSha1(blobData)))).isTrue();
    }

    @Test
    void ingestsValidPackWithMultipleObjects() {
        byte[] blob1 = "first file".getBytes();
        byte[] blob2 = "second file".getBytes();
        byte[] pack = buildPack(
                new PackObject(ObjectType.BLOB, blob1),
                new PackObject(ObjectType.BLOB, blob2));

        LooseObjectStore result = ingest(pack);

        assertThat(result).isNotNull();
        assertThat(result.contains(GitObjectId.of(blobSha1(blob1)))).isTrue();
        assertThat(result.contains(GitObjectId.of(blobSha1(blob2)))).isTrue();
    }

    @Test
    void rejectsPackWithInvalidMagic() {
        // Build a 32-byte pack with bad magic so the length check passes but the magic check fires.
        byte[] pack = new byte[32];
        pack[0] = 'X'; pack[1] = 'X'; pack[2] = 'X'; pack[3] = 'X';
        // rest is zeros (version, count, sha1 checksum all zero — checksum is irrelevant because magic is checked first)

        assertThatThrownBy(() -> ingest(pack))
                .isInstanceOf(PackParseException.class)
                .hasMessageContaining("magic");
    }

    @Test
    void rejectsPackWithWrongVersion() {
        byte[] pack = buildPackWithVersion(1);

        assertThatThrownBy(() -> ingest(pack))
                .isInstanceOf(PackParseException.class)
                .hasMessageContaining("version");
    }

    @Test
    void rejectsPackWithChecksumMismatch() {
        byte[] pack = buildPack(new PackObject(ObjectType.BLOB, "data".getBytes()));
        pack[pack.length - 1] ^= 0xff;

        assertThatThrownBy(() -> ingest(pack))
                .isInstanceOf(PackParseException.class)
                .hasMessageContaining("checksum");
    }

    @Test
    void rejectsTruncatedPack() {
        byte[] pack = buildPack(new PackObject(ObjectType.BLOB, "data".getBytes()));
        byte[] truncated = new byte[pack.length / 2];
        System.arraycopy(pack, 0, truncated, 0, truncated.length);

        assertThatThrownBy(() -> ingest(truncated))
                .isInstanceOf(PackParseException.class);
    }

    @Test
    void rejectsPackExceedingSizeLimit() {
        PackIngestor smallLimitIngestor = new PackIngestor(10);
        byte[] pack = buildPack(new PackObject(ObjectType.BLOB, "data".getBytes()));

        assertThatThrownBy(() -> ingest(smallLimitIngestor, pack))
                .isInstanceOf(PackParseException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void rejectsDeltaWithUnavailableOffsetBase() {
        byte[] pack = buildPackWithUnavailableOffsetDelta();

        assertThatThrownBy(() -> ingest(pack))
                .isInstanceOf(PackParseException.class)
                .hasMessageContaining("base object is unavailable");
    }

    @Test
    void ingestsOffsetDeltaObject() {
        byte[] source = "hello world".getBytes();
        byte[] target = "hello native".getBytes();
        byte[] pack = buildPackWithOffsetDelta(source, target);

        LooseObjectStore result = ingest(pack);

        assertThat(result.contains(GitObjectId.of(blobSha1(source)))).isTrue();
        assertThat(result.contains(GitObjectId.of(blobSha1(target)))).isTrue();
    }

    @Test
    void ingestsThinReferenceDeltaAgainstBaseStore() {
        byte[] source = "hello world".getBytes();
        byte[] target = "hello native".getBytes();
        LooseObjectStore baseStore = new LooseObjectStore();
        GitObjectId baseId = baseStore.write(ObjectType.BLOB, source);
        byte[] pack = buildPackWithReferenceDelta(baseId.value(), source, target);

        ByteBuf buffer = Unpooled.wrappedBuffer(pack);
        LooseObjectStore result;
        try {
            result = ingestor.ingest(buffer, baseStore);
        } finally {
            buffer.release();
        }

        assertThat(result.contains(GitObjectId.of(blobSha1(target)))).isTrue();
    }

    @Test
    void ingestsEmptyPack() {
        byte[] pack = buildPack();

        LooseObjectStore result = ingest(pack);

        assertThat(result).isNotNull();
    }

    private record PackObject(ObjectType type, byte[] data) {}

    private static byte[] buildPack(PackObject... objects) {
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeInt(body, 0x5041434b); // PACK
            writeInt(body, 2);          // version
            writeInt(body, objects.length);
            for (PackObject obj : objects) {
                writePackObject(body, obj.type(), obj.data());
            }
            byte[] bodyBytes = body.toByteArray();
            byte[] checksum = sha1(bodyBytes);
            ByteArrayOutputStream pack = new ByteArrayOutputStream();
            pack.write(bodyBytes);
            pack.write(checksum);
            return pack.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] buildPackWithVersion(int version) {
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeInt(body, 0x5041434b);
            writeInt(body, version);
            writeInt(body, 0);
            byte[] bodyBytes = body.toByteArray();
            byte[] checksum = sha1(bodyBytes);
            ByteArrayOutputStream pack = new ByteArrayOutputStream();
            pack.write(bodyBytes);
            pack.write(checksum);
            return pack.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] buildPackWithUnavailableOffsetDelta() {
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeInt(body, 0x5041434b);
            writeInt(body, 2);
            writeInt(body, 1);
            // ofs-delta header: type=6, size=1
            body.write(0x60 | 1); // MSB=0, type=6 (ofs-delta), size=1
            body.write(1);        // base offset (dummy)
            body.write(deflate(new byte[]{0x01, 0x01, 0x00}));
            byte[] bodyBytes = body.toByteArray();
            byte[] checksum = sha1(bodyBytes);
            ByteArrayOutputStream pack = new ByteArrayOutputStream();
            pack.write(bodyBytes);
            pack.write(checksum);
            return pack.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] buildPackWithOffsetDelta(byte[] source, byte[] target) {
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeInt(body, 0x5041434b);
            writeInt(body, 2);
            writeInt(body, 2);

            int baseOffset = body.size();
            writePackObject(body, ObjectType.BLOB, source);
            int deltaOffset = body.size();
            byte[] delta = replaceFromSixBytePrefixDelta(source, target);
            writeDeltaHeader(body, 6, delta.length);
            writeOffsetDeltaBaseDistance(body, deltaOffset - baseOffset);
            body.write(deflate(delta));

            return withChecksum(body.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] buildPackWithReferenceDelta(String baseId, byte[] source, byte[] target) {
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeInt(body, 0x5041434b);
            writeInt(body, 2);
            writeInt(body, 1);
            byte[] delta = replaceFromSixBytePrefixDelta(source, target);
            writeDeltaHeader(body, 7, delta.length);
            body.write(HexFormat.of().parseHex(baseId));
            body.write(deflate(delta));
            return withChecksum(body.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void writePackObject(ByteArrayOutputStream out, ObjectType type, byte[] data) throws IOException {
        long size = data.length;
        int typeId = type.packTypeId();
        int firstByte = (typeId << 4) | (int) (size & 0x0f);
        size >>= 4;
        if (size > 0) {
            firstByte |= 0x80;
        }
        out.write(firstByte);
        while (size > 0) {
            int b = (int) (size & 0x7f);
            size >>= 7;
            if (size > 0) {
                b |= 0x80;
            }
            out.write(b);
        }
        out.write(deflate(data));
    }

    private static void writeDeltaHeader(ByteArrayOutputStream out, int typeId, int size) {
        int firstByte = (typeId << 4) | (size & 0x0f);
        size >>= 4;
        if (size > 0) {
            firstByte |= 0x80;
        }
        out.write(firstByte);
        while (size > 0) {
            int b = size & 0x7f;
            size >>= 7;
            if (size > 0) {
                b |= 0x80;
            }
            out.write(b);
        }
    }

    private static void writeOffsetDeltaBaseDistance(ByteArrayOutputStream out, int distance) {
        if (distance < 1 || distance > 127) {
            throw new IllegalArgumentException("test helper supports only one-byte offset delta distances");
        }
        out.write(distance);
    }

    private static byte[] replaceFromSixBytePrefixDelta(byte[] source, byte[] target) {
        if (source.length < 6 || target.length < 6) {
            throw new IllegalArgumentException("test delta expects a six-byte shared prefix");
        }
        ByteArrayOutputStream delta = new ByteArrayOutputStream();
        writeDeltaVarInt(delta, source.length);
        writeDeltaVarInt(delta, target.length);
        delta.write(0x90);
        delta.write(6);
        int insertLength = target.length - 6;
        delta.write(insertLength);
        delta.write(target, 6, insertLength);
        return delta.toByteArray();
    }

    private static void writeDeltaVarInt(ByteArrayOutputStream out, int value) {
        do {
            int b = value & 0x7f;
            value >>>= 7;
            if (value > 0) {
                b |= 0x80;
            }
            out.write(b);
        } while (value > 0);
    }

    private static byte[] deflate(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(out)) {
            deflater.write(data);
        }
        return out.toByteArray();
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >> 24) & 0xff);
        out.write((value >> 16) & 0xff);
        out.write((value >> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private LooseObjectStore ingest(byte[] pack) {
        return ingest(ingestor, pack);
    }

    private static LooseObjectStore ingest(PackIngestor ingestor, byte[] pack) {
        ByteBuf buffer = Unpooled.wrappedBuffer(pack);
        try {
            return ingestor.ingest(buffer);
        } finally {
            buffer.release();
        }
    }

    private static byte[] withChecksum(byte[] bodyBytes) throws IOException {
        byte[] checksum = sha1(bodyBytes);
        ByteArrayOutputStream pack = new ByteArrayOutputStream();
        pack.write(bodyBytes);
        pack.write(checksum);
        return pack.toByteArray();
    }

    private static String blobSha1(byte[] data) {
        byte[] header = ("blob " + data.length + "\0").getBytes();
        byte[] full = new byte[header.length + data.length];
        System.arraycopy(header, 0, full, 0, header.length);
        System.arraycopy(data, 0, full, header.length, data.length);
        StringBuilder hex = new StringBuilder();
        for (byte b : sha1(full)) {
            hex.append(String.format("%02x", b & 0xff));
        }
        return hex.toString();
    }
}
