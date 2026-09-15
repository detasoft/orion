package pro.deta.orion.git.parser.v2.data;

import pro.deta.orion.git.parser.v2.id.ObjectId;

import java.util.Objects;

/**
 * Result of streaming a full object's inflated bytes into its canonical object hash without retaining content.
 * type is COMMIT, TREE, BLOB, or TAG; size is the inflated content length and objectId is the completed hash
 * of the canonical object header followed by that content. A delta instruction hash is never an ObjectId.
 * PackObjectParser produces this result only after successful end-of-entry validation. Content needed later
 * is reopened by offset from the upload. close is a no-op because this value owns no content resources.
 * Future streaming hashing uses CompressedGitObjectRead's shared decompression hook; it does not introduce
 * another inflater path. This scaffold accepts an already computed ObjectId and stores only that result.
 */
public final class HashedGitObjectRead extends CompressedGitObjectRead {
    private final ObjectId objectId;

    public HashedGitObjectRead(ObjectType type, long size, ObjectId objectId) {
        super(type, size);
        this.objectId = Objects.requireNonNull(objectId, "objectId");
    }

    public ObjectId objectId() {
        return objectId;
    }

    @Override
    public void close() {
    }
}
