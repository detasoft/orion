package pro.deta.orion.git.parser.v2.read;

import pro.deta.orion.git.parser.v2.data.ObjectType;
import pro.deta.orion.net.io.BufferedByteInput;

import java.io.IOException;
import java.util.Objects;

/**
 * Processes inflated content through a caller-provided function after shared decompression.
 * The consumer receives type, inflated size, and a borrowed inflated source, and returns the required result.
 * It may process chunks directly or produce independently owned content; no byte array or whole-object
 * buffering is imposed. Here the consumer's source is already inflated and must not be decompressed again.
 * Delta content consists of instructions, even when the object's eventual ObjectId is already known.
 * This handler does not apply deltas, retain invocation metadata, or provide positional reads itself.
 * Source ownership, nonnull results, and error propagation follow GitObjectRead. A returned GitObjectContent
 * must remain valid independently of the borrowed invocation stream and belongs to the caller.
 */
public final class ContentGitObjectRead<R> extends CompressedGitObjectRead<R> {
    private final GitObjectRead<R> consumer;

    public ContentGitObjectRead(GitObjectRead<R> consumer) {
        this.consumer = Objects.requireNonNull(consumer, "consumer");
    }

    @Override
    protected R readDecompressed(ObjectType type, long size, BufferedByteInput content) throws IOException {
        return Objects.requireNonNull(consumer.read(type, size, content), "reader result");
    }
}
