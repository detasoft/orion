package pro.deta.orion.git.parser.v2.data;

import java.io.IOException;

/**
 * Result of reading Git object data, exposing type and inflated size independently of what was retained.
 * HashedGitObjectRead retains only a full object's canonical ObjectId; ContentGitObjectRead provides access
 * to payload bytes, which may be full content or unresolved delta instructions according to type.
 * A pack's first pass chooses the result from the entry type: full objects are hashed while streaming,
 * while delta instructions are retained for the resolver. Neither result requires the whole pack in memory.
 * The caller closes the result when finished. A hash result owns no content resources; a content result
 * releases its owned read resources without closing an upload, repository, or transport input.
 */
public sealed interface GitObjectRead extends AutoCloseable permits HashedGitObjectRead, ContentGitObjectRead {
    ObjectType type();

    long size();

    @Override
    void close() throws IOException;
}
