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
import java.util.Arrays;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class PackIngestionSessionTest {
    private static final PackIngestionLimits LIMITS =
            new PackIngestionLimits(1024 * 1024, 100, 1024 * 1024);

    @Test
    void ingestsPackOneCallerOwnedByteAtATime() {
        byte[] data = "streamed object".getBytes();
        byte[] pack = pack(data);
        PackIngestor session = new PackIngestor(
                LIMITS,
                new LooseObjectStore());
        PackIngestionResult result = new PackIngestionResult.NeedInput();

        for (int index = 0; index < pack.length; index++) {
            ByteBuf fragment = Unpooled.wrappedBuffer(
                    new byte[]{pack[index]});
            int refCount = fragment.refCnt();
            try {
                result = session.accept(fragment);

                assertThat(fragment.readerIndex()).isEqualTo(1);
                assertThat(fragment.refCnt()).isEqualTo(refCount);
                if (index < pack.length - 1) {
                    assertThat(result)
                            .isInstanceOf(PackIngestionResult.NeedInput.class);
                }
            } finally {
                fragment.release();
            }
        }

        assertThat(result)
                .isInstanceOf(PackIngestionResult.Complete.class);
        LooseObjectStore quarantine =
                ((PackIngestionResult.Complete) result).quarantine();
        assertThat(quarantine.contains(GitObjectId.of(blobId(data))))
                .isTrue();
    }

    @Test
    void ingestsMultipleObjectsFromOneFragment() {
        byte[] first = "first".getBytes();
        byte[] second = "second".getBytes();
        byte[] pack = pack(first, second);
        PackIngestor session = new PackIngestor(
                LIMITS,
                new LooseObjectStore());
        ByteBuf input = Unpooled.wrappedBuffer(pack);

        PackIngestionResult result;
        try {
            result = session.accept(input);
            assertThat(input.readerIndex()).isEqualTo(pack.length);
        } finally {
            input.release();
        }

        assertThat(result)
                .isInstanceOf(PackIngestionResult.Complete.class);
        LooseObjectStore quarantine =
                ((PackIngestionResult.Complete) result).quarantine();
        assertThat(quarantine.contains(GitObjectId.of(blobId(first))))
                .isTrue();
        assertThat(quarantine.contains(GitObjectId.of(blobId(second))))
                .isTrue();
    }

    @Test
    void failsChecksumWithoutExposingQuarantine() {
        byte[] pack = pack("broken".getBytes());
        pack[pack.length - 1] ^= 1;
        PackIngestor session = new PackIngestor(
                LIMITS,
                new LooseObjectStore());

        PackIngestionResult result = accept(session, pack);

        assertThat(result)
                .isInstanceOf(PackIngestionResult.Failed.class);
        assertThat(((PackIngestionResult.Failed) result)
                .failure().getMessage()).contains("checksum");
    }

    @Test
    void rejectsBytesAfterChecksum() {
        byte[] valid = pack("data".getBytes());
        byte[] excess = Arrays.copyOf(valid, valid.length + 1);
        PackIngestor session = new PackIngestor(
                LIMITS,
                new LooseObjectStore());

        PackIngestionResult result = accept(session, excess);

        assertThat(result)
                .isInstanceOf(PackIngestionResult.Failed.class);
        assertThat(((PackIngestionResult.Failed) result)
                .failure().getMessage()).contains("after pack checksum");
    }

    @Test
    void reportsIncompletePackAtEndOfInput() {
        byte[] pack = pack("partial".getBytes());
        PackIngestor session = new PackIngestor(
                LIMITS,
                new LooseObjectStore());
        accept(session, Arrays.copyOf(pack, pack.length - 1));

        PackIngestionResult result = session.endOfInput();

        assertThat(result)
                .isInstanceOf(PackIngestionResult.Failed.class);
        assertThat(((PackIngestionResult.Failed) result)
                .failure().kind())
                .isEqualTo(PackParseException.Kind.INCOMPLETE);
    }

    @Test
    void enforcesPackByteLimit() {
        byte[] pack = pack("limited".getBytes());
        PackIngestor session = new PackIngestor(
                new PackIngestionLimits(
                        pack.length - 1,
                        10,
                        1024),
                new LooseObjectStore());

        PackIngestionResult result = accept(session, pack);

        assertLimitFailure(result);
    }

    @Test
    void enforcesObjectCountLimitFromHeader() {
        byte[] pack = pack("first".getBytes(), "second".getBytes());
        PackIngestor session = new PackIngestor(
                new PackIngestionLimits(
                        1024,
                        1,
                        1024),
                new LooseObjectStore());

        PackIngestionResult result = accept(session, pack);

        assertLimitFailure(result);
    }

    @Test
    void enforcesInflatedObjectLimitBeforeAllocation() {
        byte[] pack = pack("too large".getBytes());
        PackIngestor session = new PackIngestor(
                new PackIngestionLimits(
                        1024,
                        10,
                        4),
                new LooseObjectStore());

        PackIngestionResult result = accept(session, pack);

        assertLimitFailure(result);
    }

    @Test
    void closeIsIdempotentAndLaterInputReturnsTypedFailure() {
        PackIngestor session = new PackIngestor(
                LIMITS,
                new LooseObjectStore());

        session.close();
        session.close();
        PackIngestionResult result = accept(session, new byte[]{1});

        assertThat(result)
                .isInstanceOf(PackIngestionResult.Failed.class);
        assertThat(((PackIngestionResult.Failed) result)
                .failure()).isNotNull();
    }

    @Test
    void completedQuarantineIsTransferredOnlyOnce() {
        PackIngestor session = new PackIngestor(
                LIMITS,
                new LooseObjectStore());

        PackIngestionResult completed =
                accept(session, pack("once".getBytes()));
        PackIngestionResult repeated =
                accept(session, new byte[0]);

        assertThat(completed)
                .isInstanceOf(PackIngestionResult.Complete.class);
        assertThat(repeated)
                .isInstanceOf(PackIngestionResult.Failed.class);
    }

    private static PackIngestionResult accept(
            PackIngestor session,
            byte[] bytes) {
        ByteBuf input = Unpooled.wrappedBuffer(bytes);
        try {
            return session.accept(input);
        } finally {
            input.release();
        }
    }

    private static void assertLimitFailure(
            PackIngestionResult result) {
        assertThat(result)
                .isInstanceOf(PackIngestionResult.Failed.class);
        assertThat(((PackIngestionResult.Failed) result)
                .failure().kind())
                .isEqualTo(PackParseException.Kind.LIMIT_EXCEEDED);
    }

    private static byte[] pack(byte[]... objects) {
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeInt(body, 0x5041434b);
            writeInt(body, 2);
            writeInt(body, objects.length);
            for (byte[] object : objects) {
                writeObject(body, object);
            }
            byte[] bodyBytes = body.toByteArray();
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            result.write(bodyBytes);
            result.write(sha1(bodyBytes));
            return result.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void writeObject(
            ByteArrayOutputStream output,
            byte[] data) throws IOException {
        int size = data.length;
        int first = (ObjectType.BLOB.packTypeId() << 4)
                | (size & 0x0f);
        size >>>= 4;
        if (size != 0) {
            first |= 0x80;
        }
        output.write(first);
        while (size != 0) {
            int next = size & 0x7f;
            size >>>= 7;
            if (size != 0) {
                next |= 0x80;
            }
            output.write(next);
        }
        try (DeflaterOutputStream deflater =
                     new DeflaterOutputStream(output)) {
            deflater.write(data);
        }
    }

    private static void writeInt(
            ByteArrayOutputStream output,
            int value) {
        output.write((value >>> 24) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    private static String blobId(byte[] data) {
        byte[] header = ("blob " + data.length + "\0").getBytes();
        MessageDigest digest = sha1Digest();
        digest.update(header);
        return java.util.HexFormat.of().formatHex(digest.digest(data));
    }

    private static byte[] sha1(byte[] bytes) {
        return sha1Digest().digest(bytes);
    }

    private static MessageDigest sha1Digest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
