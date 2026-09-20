package pro.deta.orion.git.parser.v2.read;

import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Optional;

public final class HashedGitObjectRead extends CompressedGitObjectRead<ObjectId> {
    @Override
    protected ObjectId readDecompressed(GitObjectType type, long size, Optional<ObjectId> baseId,
            BufferedByteInputV2 content)
            throws IOException {
        String name = switch (type) {
            case COMMIT -> "commit";
            case TREE -> "tree";
            case BLOB -> "blob";
            case TAG -> "tag";
            case OFS_DELTA, REF_DELTA -> throw new IOException("Delta instructions have no object ID");
        };
        MessageDigest digest = GitHashAlgorithm.SHA1.newDigest();
        digest.update((name + " " + size + "\0").getBytes(StandardCharsets.US_ASCII));
        ByteBuffer buffer;
        while ((buffer = content.buffer()) != null) {
            digest.update(buffer);
        }
        return new ObjectId(digest.digest());
    }
}
