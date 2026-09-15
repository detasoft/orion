package pro.deta.orion.git.parser.v2.read;

import pro.deta.orion.git.parser.v2.data.ObjectType;
import pro.deta.orion.net.io.BufferedByteInput;

import java.io.IOException;
import java.util.Objects;

/**
 * Processes an object's original compressed bytes without decompression, hashing, or delta application.
 * The supplied consumer receives type, inflated size, and the bounded raw source, and returns the requested
 * result. For example, it may stream bytes to an output and return a count without collecting them in memory.
 * There is no separate compressedSize or stored object metadata: the source supplies its boundary and each
 * invocation supplies its metadata. This processor neither owns nor closes the source or consumer resources.
 */
public final class RawGitObjectRead<R> implements GitObjectRead<R> {
    private final GitObjectRead<R> consumer;

    public RawGitObjectRead(GitObjectRead<R> consumer) {
        this.consumer = Objects.requireNonNull(consumer, "consumer");
    }

    @Override
    public R read(ObjectType type, long inflatedSize, BufferedByteInput rawSource) throws IOException {
        return Objects.requireNonNull(consumer.read(type, inflatedSize, rawSource), "reader result");
    }
}
