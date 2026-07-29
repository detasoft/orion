package pro.deta.orion.git.nativestorage.pack;

import pro.deta.orion.git.nativestorage.object.LooseObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.zip.DeflaterOutputStream;

public final class NoDeltaPackBuilder {
    private static final byte[] PACK_MAGIC = {'P', 'A', 'C', 'K'};
    private static final int PACK_VERSION = 2;

    public byte[] build(List<LooseObject> objects) {
        Objects.requireNonNull(objects, "objects");
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeHeader(body, objects.size());
            for (LooseObject object : sorted(objects)) {
                writeObject(body, object);
            }
            byte[] bodyBytes = body.toByteArray();
            byte[] checksum = sha1(bodyBytes);
            ByteArrayOutputStream pack = new ByteArrayOutputStream(bodyBytes.length + checksum.length);
            pack.write(bodyBytes);
            pack.write(checksum);
            return pack.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<LooseObject> sorted(List<LooseObject> objects) {
        List<LooseObject> sorted = new ArrayList<>(objects);
        sorted.sort(Comparator.comparing(object -> object.id().value()));
        return sorted;
    }

    private static void writeHeader(ByteArrayOutputStream output, int objectCount) {
        output.writeBytes(PACK_MAGIC);
        writeInt(output, PACK_VERSION);
        writeInt(output, objectCount);
    }

    private static void writeObject(ByteArrayOutputStream output, LooseObject object) throws IOException {
        Objects.requireNonNull(object, "object");
        byte[] data = object.data();
        writeObjectHeader(output, object.type().packTypeId(), data.length);
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(output)) {
            deflater.write(data);
        }
    }

    private static void writeObjectHeader(ByteArrayOutputStream output, int typeId, long size) {
        int firstByte = (typeId << 4) | (int) (size & 0x0f);
        size >>>= 4;
        if (size > 0) {
            firstByte |= 0x80;
        }
        output.write(firstByte);
        while (size > 0) {
            int nextByte = (int) (size & 0x7f);
            size >>>= 7;
            if (size > 0) {
                nextByte |= 0x80;
            }
            output.write(nextByte);
        }
    }

    private static void writeInt(ByteArrayOutputStream output, int value) {
        output.write((value >>> 24) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }
}
