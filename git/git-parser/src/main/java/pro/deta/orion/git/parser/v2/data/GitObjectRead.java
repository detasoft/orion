package pro.deta.orion.git.parser.v2.data;

import java.io.IOException;

/**
 * Result of reading Git object data, exposing its type and the size of the represented bytes.
 * size counts compressed bytes for RawGitObjectRead and inflated payload bytes for CompressedGitObjectRead
 * and its subclasses, including the full content length used to compute a HashedGitObjectRead result.
 * RawGitObjectRead provides original compressed bytes without decompression. CompressedGitObjectRead is
 * the separate shared decompression branch, extended by HashedGitObjectRead and ContentGitObjectRead.
 * HashedGitObjectRead retains only a full object's canonical ObjectId; ContentGitObjectRead provides access
 * to payload bytes, which may be full content or unresolved delta instructions according to type.
 * A pack's first pass chooses the result from the entry type: full objects are hashed while streaming,
 * while delta instructions are retained for the resolver. Neither result requires the whole pack in memory.
 * The caller closes the result when finished. A hash result owns no content resources; a content result
 * releases its owned read resources without closing an upload, repository, or transport input.
 */
public sealed interface GitObjectRead extends AutoCloseable permits RawGitObjectRead, CompressedGitObjectRead {
    ObjectType type();

    long size();

    @Override
    void close() throws IOException;
}
