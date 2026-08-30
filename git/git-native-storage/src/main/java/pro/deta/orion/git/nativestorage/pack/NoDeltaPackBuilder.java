package pro.deta.orion.git.nativestorage.pack;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.object.LooseObject;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.zip.Deflater;

public final class NoDeltaPackBuilder {
    public NativePackProducer producer(
            LooseObjectStore objects,
            Collection<GitObjectId> objectIds) {
        Objects.requireNonNull(objects, "objects");
        Objects.requireNonNull(objectIds, "objectIds");
        List<GitObjectId> sorted =
                new ArrayList<>(objectIds);
        sorted.sort(Comparator.comparing(GitObjectId::value));
        return new Producer(
                objects,
                List.copyOf(sorted));
    }

    private static final class Producer
            implements NativePackProducer {
        private static final byte[] PACK_MAGIC =
                {'P', 'A', 'C', 'K'};
        private static final int PACK_VERSION = 2;
        private static final int SCRATCH_SIZE = 8 * 1024;

        private final LooseObjectStore objects;
        private final List<GitObjectId> objectIds;
        private final MessageDigest digest = sha1();
        private final byte[] scratch = new byte[SCRATCH_SIZE];

        private Phase phase = Phase.HEADER;
        private int objectIndex;
        private byte[] pending;
        private int pendingOffset;
        private int pendingLength;
        private boolean digestPending;
        private Deflater deflater;
        private boolean closed;

        private Producer(
                LooseObjectStore objects,
                List<GitObjectId> objectIds) {
            this.objects = objects;
            this.objectIds = objectIds;
        }

        @Override
        public Result produce(ByteBuf destination) {
            Objects.requireNonNull(destination, "destination");
            if (closed) {
                throw new IllegalStateException(
                        "Native pack producer is closed");
            }
            while (destination.isWritable()) {
                if (pending != null) {
                    writePending(destination);
                    if (pending != null) {
                        return Result.MORE;
                    }
                    continue;
                }
                prepareNext();
                if (phase == Phase.COMPLETED
                        && pending == null) {
                    return Result.COMPLETED;
                }
            }
            return phase == Phase.COMPLETED
                    && pending == null
                    ? Result.COMPLETED
                    : Result.MORE;
        }

        private void writePending(ByteBuf destination) {
            int length = Math.min(
                    destination.writableBytes(),
                    pendingLength - pendingOffset);
            destination.writeBytes(
                    pending,
                    pendingOffset,
                    length);
            if (digestPending) {
                digest.update(
                        pending,
                        pendingOffset,
                        length);
            }
            pendingOffset += length;
            if (pendingOffset == pendingLength) {
                pending = null;
                pendingOffset = 0;
                pendingLength = 0;
                digestPending = false;
            }
        }

        private void prepareNext() {
            switch (phase) {
                case HEADER -> {
                    pending = packHeader(objectIds.size());
                    pendingLength = pending.length;
                    digestPending = true;
                    phase = objectIds.isEmpty()
                            ? Phase.TRAILER
                            : Phase.OBJECT_HEADER;
                }
                case OBJECT_HEADER -> {
                    LooseObject object = objects.read(
                            objectIds.get(objectIndex))
                            .orElseThrow(() ->
                                    new IllegalStateException(
                                            "Pack object is unavailable"));
                    byte[] data = object.data();
                    pending = objectHeader(
                            object.type().packTypeId(),
                            data.length);
                    pendingLength = pending.length;
                    digestPending = true;
                    deflater = new Deflater();
                    deflater.setInput(data);
                    deflater.finish();
                    phase = Phase.OBJECT_DATA;
                }
                case OBJECT_DATA -> prepareCompressedData();
                case TRAILER -> {
                    pending = digest.digest();
                    pendingLength = pending.length;
                    digestPending = false;
                    phase = Phase.COMPLETED;
                }
                case COMPLETED -> {
                }
            }
        }

        private void prepareCompressedData() {
            int length = deflater.deflate(scratch);
            if (length > 0) {
                pending = scratch;
                pendingLength = length;
                digestPending = true;
            }
            if (deflater.finished()) {
                deflater.end();
                deflater = null;
                objectIndex++;
                phase = objectIndex < objectIds.size()
                        ? Phase.OBJECT_HEADER
                        : Phase.TRAILER;
            } else if (length == 0) {
                throw new IllegalStateException(
                        "Pack deflater made no progress");
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (deflater != null) {
                deflater.end();
                deflater = null;
            }
            pending = null;
        }

        private static byte[] packHeader(int objectCount) {
            byte[] header = new byte[12];
            System.arraycopy(
                    PACK_MAGIC,
                    0,
                    header,
                    0,
                    PACK_MAGIC.length);
            writeInt(header, 4, PACK_VERSION);
            writeInt(header, 8, objectCount);
            return header;
        }

        private static byte[] objectHeader(
                int typeId,
                long size) {
            byte[] header = new byte[10];
            int offset = 0;
            int firstByte =
                    (typeId << 4) | (int) (size & 0x0f);
            size >>>= 4;
            if (size > 0) {
                firstByte |= 0x80;
            }
            header[offset++] = (byte) firstByte;
            while (size > 0) {
                int nextByte = (int) (size & 0x7f);
                size >>>= 7;
                if (size > 0) {
                    nextByte |= 0x80;
                }
                header[offset++] = (byte) nextByte;
            }
            return Arrays.copyOf(header, offset);
        }

        private static void writeInt(
                byte[] destination,
                int offset,
                int value) {
            destination[offset] =
                    (byte) (value >>> 24);
            destination[offset + 1] =
                    (byte) (value >>> 16);
            destination[offset + 2] =
                    (byte) (value >>> 8);
            destination[offset + 3] =
                    (byte) value;
        }

        private static MessageDigest sha1() {
            try {
                return MessageDigest.getInstance("SHA-1");
            } catch (NoSuchAlgorithmException error) {
                throw new IllegalStateException(
                        "SHA-1 not available",
                        error);
            }
        }

        private enum Phase {
            HEADER,
            OBJECT_HEADER,
            OBJECT_DATA,
            TRAILER,
            COMPLETED
        }
    }
}
