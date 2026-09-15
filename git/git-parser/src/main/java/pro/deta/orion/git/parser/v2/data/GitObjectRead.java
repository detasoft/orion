package pro.deta.orion.git.parser.v2.data;

import pro.deta.orion.net.io.BufferedByteInput;

import java.io.IOException;

/**
 * Functional processor invoked by storage or pack parsing to produce a caller-selected result.
 * read receives the physical type, declared inflated payload size, and a borrowed bounded source. Storage
 * and pack parsing supply the object's zlib stream without pack headers or delta base references; the consumer
 * inside ContentGitObjectRead receives already inflated bytes. The invocation boundary defines representation.
 * The provider owns input bounds and cleanup; processors must neither close nor retain the borrowed source.
 * A result needing later reads must own an independent handle to retained backing data.
 *
 * <p>RawGitObjectRead processes the original bytes. CompressedGitObjectRead provides the shared decompression
 * path used by HashedGitObjectRead and ContentGitObjectRead. Delta types describe instructions, not restored
 * content; applying them to bases belongs to the resolver. Type and size are input metadata, not processor
 * state. Result metadata can be captured by the consumer when needed. Processing failures propagate as
 * IOException. Results must be nonnull; storage absence is distinct from processor output or failure.
 * The provider drains and validates unread input before reporting successful processing. These handlers
 * have no read-handle lifecycle; callers own any resources returned as their result. Resource-bearing results
 * implement AutoCloseable so the provider can close an unreturned result if final validation fails.
 */
@FunctionalInterface
public interface GitObjectRead<R> {
    R read(ObjectType type, long inflatedSize, BufferedByteInput source) throws IOException;
}
