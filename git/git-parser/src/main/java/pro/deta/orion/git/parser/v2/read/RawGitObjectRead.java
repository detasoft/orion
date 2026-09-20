package pro.deta.orion.git.parser.v2.read;

import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

public final class RawGitObjectRead<R> implements GitObjectRead<R> {
    private final GitObjectRead<R> consumer;

    public RawGitObjectRead(GitObjectRead<R> consumer) {
        this.consumer = Objects.requireNonNull(consumer, "consumer");
    }

    @Override
    public R read(GitObjectType type, long inflatedSize, Optional<ObjectId> baseId,
            BufferedByteInputV2 rawSource) throws IOException {
        return Objects.requireNonNull(consumer.read(type, inflatedSize, baseId, rawSource), "reader result");
    }
}
