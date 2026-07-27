package pro.deta.orion.git.nativestorage.pack;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

        LooseObjectStore result = ingestor.ingest(new ByteArrayInputStream(pack));

        assertThat(result).isNotNull();
    }

    @Test
    void ingestsValidPackWithMultipleObjects() {
        byte[] blob1 = "first file".getBytes();
        byte[] blob2 = "second file".getBytes();
        byte[] pack = buildPack(
                new PackObject(ObjectType.BLOB, blob1),
                new PackObject(ObjectType.BLOB, blob2));

        LooseObjectStore result = ingestor.ingest(new ByteArrayInputStream(pack));

        assertThat(result).isNotNull();
    }

    @Test
    void rejectsPackWithInvalidMagic() {
        // Build a 32-byte pack with bad magic so the length check passes but the magic check fires.
        byte[] pack = new byte[32];
        pack[0] = 'X'; pack[1] = 'X'; pack[2] = 'X'; pack[3] = 'X';
        // rest is zeros (version, count, sha1 checksum all zero — checksum is irrelevant because magic is checked first)

        assertThatThrownBy(() -> ingestor.ingest(new ByteArrayInputStream(pack)))
                .isInstanceOf(PackParseException.class)
                .hasMessageContaining("magic");
    }

    @Test
    void rejectsPackWithWrongVersion() {
        byte[] pack = buildPackWithVersion(1);

        assertThatThrownBy(() -> ingestor.ingest(new ByteArrayInputStream(pack)))
                .isInstanceOf(PackParseException.class)
                .hasMessageContaining("version");
    }

    @Test
    void rejectsPackWithChecksumMismatch() {
        byte[] pack = buildPack(new PackObject(ObjectType.BLOB, "data".getBytes()));
        pack[pack.length - 1] ^= 0xff;

        assertThatThrownBy(() -> ingestor.ingest(new ByteArrayInputStream(pack)))
                .isInstanceOf(PackParseException.class)
                .hasMessageContaining("checksum");
    }

    @Test
    void rejectsTruncatedPack() {
        byte[] pack = buildPack(new PackObject(ObjectType.BLOB, "data".getBytes()));
        byte[] truncated = new byte[pack.length / 2];
        System.arraycopy(pack, 0, truncated, 0, truncated.length);

        assertThatThrownBy(() -> ingestor.ingest(new ByteArrayInputStream(truncated)))
                .isInstanceOf(PackParseException.class);
    }

    @Test
    void rejectsPackExceedingSizeLimit() {
        PackIngestor smallLimitIngestor = new PackIngestor(10);
        byte[] pack = buildPack(new PackObject(ObjectType.BLOB, "data".getBytes()));

        assertThatThrownBy(() -> smallLimitIngestor.ingest(new ByteArrayInputStream(pack)))
                .isInstanceOf(PackParseException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void rejectsDeltaObjects() {
        byte[] pack = buildPackWithDeltaObject();

        assertThatThrownBy(() -> ingestor.ingest(new ByteArrayInputStream(pack)))
                .isInstanceOf(PackParseException.class)
                .hasMessageContaining("Delta");
    }

    @Test
    void ingestsEmptyPack() {
        byte[] pack = buildPack();

        LooseObjectStore result = ingestor.ingest(new ByteArrayInputStream(pack));

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

    private static byte[] buildPackWithDeltaObject() {
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
}
