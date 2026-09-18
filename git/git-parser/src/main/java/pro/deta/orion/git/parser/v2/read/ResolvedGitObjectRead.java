package pro.deta.orion.git.parser.v2.read;

import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.net.io.BufferedByteInput;
import pro.deta.orion.net.io.InputStreamBufferedByteInput;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ResolvedGitObjectRead<R> extends CompressedGitObjectRead<R> {
    private final GitStorageApi storage;
    private final GitObjectRead<R> consumer;
    private final Set<ObjectId> activeBases;

    public ResolvedGitObjectRead(GitStorageApi storage, GitObjectRead<R> consumer) {
        this(storage, consumer, null);
    }

    private ResolvedGitObjectRead(GitStorageApi storage, GitObjectRead<R> consumer, Set<ObjectId> activeBases) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.activeBases = activeBases;
    }

    @Override
    protected R readDecompressed(GitObjectType type, long size, Optional<ObjectId> baseId,
                                 BufferedByteInput content) throws IOException {
        if (type == GitObjectType.OFS_DELTA) {
            throw new IllegalStateException("not yet supported");
        }
        if (type != GitObjectType.REF_DELTA) {
            return consumer.read(type, size, Optional.empty(), content);
        }
        ObjectId id = baseId.orElseThrow(() -> new IOException("REF_DELTA has no base ObjectId"));
        Set<ObjectId> path = activeBases == null ? new HashSet<>() : activeBases;
        if (!path.add(id)) {
            throw new IOException("Cyclic delta base: " + id.toHex());
        }
        Base base;
        try {
            var baseReader = new ResolvedGitObjectRead<Base>(storage, (baseType, baseSize, unused, input) -> {
                if (baseSize > Integer.MAX_VALUE - 8) {
                    throw new IOException("Delta base is too large for in-memory resolution");
                }
                return new Base(baseType, input.readBytes((int) baseSize));
            }, path);
            base = storage.readObject(id, baseReader)
                    .orElseThrow(() -> new IOException("Missing delta base: " + id.toHex()));
        } finally {
            path.remove(id);
        }
        var restored = new DeltaInput(content, base.bytes());
        R value = null;
        try {
            value = Objects.requireNonNull(consumer.read(base.type(), restored.size, Optional.empty(),
                    new InputStreamBufferedByteInput(restored)), "reader result");
            byte[] discard = new byte[8192];
            while (restored.read(discard) != -1) {
                // Validate all delta instructions even if the consumer returns early.
            }
            return value;
        } catch (IOException | RuntimeException | Error failure) {
            if (value instanceof AutoCloseable resource) {
                try {
                    resource.close();
                } catch (Throwable cleanup) {
                    if (cleanup != failure) {
                        failure.addSuppressed(cleanup);
                    }
                }
            }
            throw failure;
        }
    }

    private record Base(GitObjectType type, byte[] bytes) {}

    private static final class DeltaInput extends InputStream {
        private final BufferedByteInput instructions;
        private final byte[] base;
        private final byte[] singleByte = new byte[1];
        private final long size;
        private long remaining;
        private int instructionRemaining;
        private int copyOffset;
        private boolean copy;

        private DeltaInput(BufferedByteInput instructions, byte[] base) throws IOException {
            this.instructions = instructions;
            this.base = base;
            if (readSize() != base.length) {
                throw new IOException("Delta source size does not match base object");
            }
            size = readSize();
            remaining = size;
        }

        private long readSize() throws IOException {
            long value = 0;
            for (int shift = 0; shift < 63; shift += 7) {
                int next = instructions.readUnsignedByte();
                value |= (long) (next & 127) << shift;
                if ((next & 128) == 0) {
                    return value;
                }
            }
            throw new IOException("Delta size overflows a signed long");
        }

        @Override
        public int read() throws IOException {
            return read(singleByte, 0, 1) == -1 ? -1 : singleByte[0] & 255;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, target.length);
            if (length == 0) {
                return 0;
            }
            if (remaining == 0) {
                try {
                    instructions.readUnsignedByte();
                } catch (EOFException end) {
                    return -1;
                }
                throw new IOException("Delta instructions exceed target size");
            }
            if (instructionRemaining == 0) {
                int opcode = instructions.readUnsignedByte();
                if (opcode == 0) {
                    throw new IOException("Invalid zero delta opcode");
                }
                copy = (opcode & 128) != 0;
                if (copy) {
                    long position = 0;
                    int count = 0;
                    for (int i = 0; i < 4; i++) {
                        if ((opcode & (1 << i)) != 0) {
                            position |= (long) instructions.readUnsignedByte() << (8 * i);
                        }
                    }
                    for (int i = 0; i < 3; i++) {
                        if ((opcode & (16 << i)) != 0) {
                            count |= instructions.readUnsignedByte() << (8 * i);
                        }
                    }
                    instructionRemaining = count == 0 ? 0x10000 : count;
                    if (position + instructionRemaining > base.length) {
                        throw new IOException("Delta copy exceeds base object size");
                    }
                    copyOffset = (int) position;
                } else {
                    instructionRemaining = opcode;
                }
                if (instructionRemaining > remaining) {
                    throw new IOException("Delta instruction exceeds target size");
                }
            }
            int count = Math.min(length, instructionRemaining);
            if (copy) {
                System.arraycopy(base, copyOffset, target, offset, count);
                copyOffset += count;
            } else {
                for (int i = 0; i < count; i++) {
                    target[offset + i] = (byte) instructions.readUnsignedByte();
                }
            }
            instructionRemaining -= count;
            remaining -= count;
            return count;
        }
    }
}
