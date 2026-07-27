package pro.deta.orion.git.client.repository;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

final class InMemoryGitRepositoryContents implements GitRepositoryContents {
    private static final int READ_CHUNK_SIZE = 3;

    private final Map<GitPackId, byte[]> packs = new LinkedHashMap<>();

    @Override
    public synchronized GitPackReader openPack(GitPackId packId)
            throws GitRepositoryAccessException {
        byte[] pack = packs.get(packId);
        if (pack == null) {
            throw new GitRepositoryAccessException(
                    GitRepositoryAccessException.Operation.OPEN_PACK,
                    false,
                    "Pack is not available");
        }
        return new Reader(pack);
    }

    @Override
    public GitPackWriter beginPack() {
        return new Writer();
    }

    synchronized int storedPackCount() {
        return packs.size();
    }

    private final class Writer implements GitPackWriter {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private GitPackId completedId;
        private boolean closed;

        @Override
        public void write(ByteBuf chunk) throws GitRepositoryAccessException {
            if (closed || completedId != null) {
                throw writeFailure("Pack writer is not open");
            }
            byte[] copy = new byte[chunk.readableBytes()];
            chunk.getBytes(chunk.readerIndex(), copy);
            bytes.writeBytes(copy);
        }

        @Override
        public GitPackId complete() throws GitRepositoryAccessException {
            if (closed && completedId == null) {
                throw writeFailure("Pack writer was aborted");
            }
            if (completedId != null) {
                return completedId;
            }
            byte[] pack = bytes.toByteArray();
            completedId = new GitPackId(hex(sha256(pack)));
            synchronized (InMemoryGitRepositoryContents.this) {
                packs.put(completedId, pack);
            }
            return completedId;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class Reader implements GitPackReader {
        private final byte[] pack;
        private int offset;
        private boolean closed;

        private Reader(byte[] pack) {
            this.pack = Arrays.copyOf(pack, pack.length);
        }

        @Override
        public ByteBuf read() throws GitRepositoryAccessException {
            if (closed) {
                throw new GitRepositoryAccessException(
                        GitRepositoryAccessException.Operation.READ_PACK,
                        false,
                        "Pack reader is closed");
            }
            if (offset == pack.length) {
                return null;
            }
            int end = Math.min(pack.length, offset + READ_CHUNK_SIZE);
            ByteBuf chunk = Unpooled.wrappedBuffer(Arrays.copyOfRange(pack, offset, end));
            offset = end;
            return chunk;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static GitRepositoryAccessException writeFailure(String message) {
        return new GitRepositoryAccessException(
                GitRepositoryAccessException.Operation.WRITE_PACK,
                false,
                message);
    }

    private static byte[] sha256(byte[] bytes) throws GitRepositoryAccessException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new GitRepositoryAccessException(
                    GitRepositoryAccessException.Operation.WRITE_PACK,
                    false,
                    "SHA-256 is unavailable",
                    e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 0xf, 16));
            result.append(Character.forDigit(value & 0xf, 16));
        }
        return result.toString();
    }
}
