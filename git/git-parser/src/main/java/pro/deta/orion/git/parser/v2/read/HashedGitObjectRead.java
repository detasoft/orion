package pro.deta.orion.git.parser.v2.read;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import pro.deta.orion.git.parser.v2.data.ObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.net.io.BufferedByteInput;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Processes a full object's inflated bytes into its canonical ObjectId without retaining its content.
 * Inherited read performs decompression, then readDecompressed hashes the canonical type/size header and
 * content incrementally. Types are COMMIT, TREE, BLOB, or TAG. Delta types are rejected: a hash of instructions
 * is not the reconstructed object's ID, which must be computed by the resolver after applying its base.
 * No ObjectId, type, size, or input handle is stored in this processor.
 */
public final class HashedGitObjectRead extends CompressedGitObjectRead<ObjectId> {
    @Override
    protected ObjectId readDecompressed(ObjectType type, long size, BufferedByteInput content)
            throws IOException {
        String name = switch (type) {
            case COMMIT -> "commit";
            case TREE -> "tree";
            case BLOB -> "blob";
            case TAG -> "tag";
            case OFS_DELTA, REF_DELTA -> throw new IOException("Delta instructions have no object ID");
        };
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-1 is required for Git object IDs", error);
        }
        digest.update((name + " " + size + "\0").getBytes(StandardCharsets.US_ASCII));
        ByteBuf buffer = Unpooled.buffer(8192, 8192);
        try {
            int count;
            while ((count = content.readInto(buffer, buffer.writableBytes())) != 0) {
                digest.update(buffer.array(), buffer.arrayOffset() + buffer.readerIndex(), count);
                buffer.clear();
            }
            return new ObjectId(digest.digest());
        } finally {
            buffer.release();
        }
    }
}
