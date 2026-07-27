package pro.deta.orion.git.nativestorage.pack;

import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public final class PackIngestor {
    private static final byte[] PACK_MAGIC = {'P', 'A', 'C', 'K'};
    private static final int PACK_VERSION = 2;
    private static final int SHA1_BYTES = 20;

    private final long maxPackBytes;

    public PackIngestor(long maxPackBytes) {
        if (maxPackBytes < 1) {
            throw new IllegalArgumentException("maxPackBytes must be positive");
        }
        this.maxPackBytes = maxPackBytes;
    }

    public LooseObjectStore ingest(InputStream packStream) {
        try {
            return doIngest(packStream);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private LooseObjectStore doIngest(InputStream packStream) throws IOException {
        byte[] packData = readAll(packStream);
        if (packData.length < PACK_MAGIC.length + 8 + SHA1_BYTES) {
            throw new PackParseException("Pack too short to be valid");
        }

        byte[] magic = Arrays.copyOf(packData, PACK_MAGIC.length);
        if (!Arrays.equals(magic, PACK_MAGIC)) {
            throw new PackParseException("Invalid pack magic bytes");
        }

        byte[] computedChecksum = sha1(packData, 0, packData.length - SHA1_BYTES);
        byte[] trailingChecksum = Arrays.copyOfRange(packData, packData.length - SHA1_BYTES, packData.length);
        if (!Arrays.equals(computedChecksum, trailingChecksum)) {
            throw new PackParseException("Pack checksum mismatch");
        }

        PackBuffer buf = new PackBuffer(packData, 0, packData.length - SHA1_BYTES);
        buf.readExact(PACK_MAGIC.length);

        int version = buf.readInt();
        if (version != PACK_VERSION) {
            throw new PackParseException("Unsupported pack version: " + version);
        }

        int objectCount = buf.readInt();
        if (objectCount < 0) {
            throw new PackParseException("Invalid pack object count");
        }

        LooseObjectStore quarantine = new LooseObjectStore();
        for (int i = 0; i < objectCount; i++) {
            ingestObject(buf, quarantine);
        }

        if (buf.hasRemaining()) {
            throw new PackParseException("Unexpected trailing data after last pack object");
        }

        return quarantine;
    }

    private static void ingestObject(PackBuffer buf, LooseObjectStore quarantine) {
        int firstByte = buf.readByte();
        int typeId = (firstByte >> 4) & 0x07;
        long size = firstByte & 0x0f;
        int shift = 4;
        int current = firstByte;
        while ((current & 0x80) != 0) {
            current = buf.readByte();
            size |= (long) (current & 0x7f) << shift;
            shift += 7;
        }

        if (typeId == 6 || typeId == 7) {
            throw new PackParseException("Delta objects (ofs-delta, ref-delta) are not supported");
        }

        ObjectType type;
        try {
            type = ObjectType.fromPackTypeId(typeId);
        } catch (IllegalArgumentException e) {
            throw new PackParseException("Unknown pack object type id: " + typeId);
        }

        byte[] data = inflate(buf, size);
        quarantine.write(type, data);
    }

    private static byte[] inflate(PackBuffer buf, long expectedSize) {
        Inflater inflater = new Inflater();
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(expectedSize, 1024 * 1024));
        byte[] outputBuf = new byte[4096];
        long totalOutput = 0;
        int startPos = buf.position();

        try {
            inflater.setInput(buf.array(), buf.position(), buf.remaining());
            while (!inflater.finished()) {
                int produced;
                try {
                    produced = inflater.inflate(outputBuf);
                } catch (DataFormatException e) {
                    throw new PackParseException("Invalid deflate stream in pack: " + e.getMessage());
                }
                if (produced > 0) {
                    output.write(outputBuf, 0, produced);
                    totalOutput += produced;
                    if (totalOutput > expectedSize) {
                        throw new PackParseException("Inflated size exceeds declared object size");
                    }
                } else if (inflater.needsInput()) {
                    throw new PackParseException("Truncated pack: deflate stream ended unexpectedly");
                }
            }
        } finally {
            int consumed = (buf.remaining()) - inflater.getRemaining();
            buf.skip(consumed);
            inflater.end();
        }

        if (totalOutput != expectedSize) {
            throw new PackParseException(
                    "Inflated size " + totalOutput + " does not match declared size " + expectedSize);
        }
        return output.toByteArray();
    }

    private byte[] readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = stream.read(buf)) >= 0) {
            total += n;
            if (total > maxPackBytes) {
                throw new PackParseException("Pack exceeds size limit of " + maxPackBytes + " bytes");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static byte[] sha1(byte[] data, int offset, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(data, offset, length);
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }

    private static final class PackBuffer {
        private final byte[] data;
        private int pos;
        private final int limit;

        PackBuffer(byte[] data, int offset, int limit) {
            this.data = data;
            this.pos = offset;
            this.limit = limit;
        }

        byte[] array() {
            return data;
        }

        int position() {
            return pos;
        }

        int remaining() {
            return limit - pos;
        }

        boolean hasRemaining() {
            return pos < limit;
        }

        int readByte() {
            if (pos >= limit) {
                throw new PackParseException("Truncated pack: expected more data");
            }
            return data[pos++] & 0xff;
        }

        int readInt() {
            byte[] bytes = readExact(4);
            return ((bytes[0] & 0xff) << 24)
                    | ((bytes[1] & 0xff) << 16)
                    | ((bytes[2] & 0xff) << 8)
                    | (bytes[3] & 0xff);
        }

        byte[] readExact(int count) {
            if (pos + count > limit) {
                throw new PackParseException("Truncated pack: expected " + count + " bytes");
            }
            byte[] result = Arrays.copyOfRange(data, pos, pos + count);
            pos += count;
            return result;
        }

        void skip(int count) {
            if (pos + count > limit) {
                throw new PackParseException("Truncated pack: skip past end");
            }
            pos += count;
        }
    }
}
