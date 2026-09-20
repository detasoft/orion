package pro.deta.orion.git.parser.v2.read;

import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.util.Optional;

public final class ExistsGitObjectRead implements GitObjectRead<Boolean> {
    @Override
    public Boolean read(GitObjectType type, long inflatedSize, Optional<ObjectId> baseId,
                        BufferedByteInputV2 source) {
        return Boolean.TRUE;
    }
}
