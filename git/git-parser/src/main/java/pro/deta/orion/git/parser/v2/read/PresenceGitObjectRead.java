package pro.deta.orion.git.parser.v2.read;

import pro.deta.orion.git.parser.v2.data.ObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.net.io.BufferedByteInput;

import java.util.Optional;

/**
 * Returns TRUE when storage invokes the processor for a found object, without reading its content.
 * Storage reports absence through Optional.empty and remains responsible for draining, validating,
 * and closing the borrowed source. This processor neither retains nor closes it.
 */
public final class PresenceGitObjectRead implements GitObjectRead<Boolean> {
    @Override
    public Boolean read(ObjectType type, long inflatedSize, Optional<ObjectId> baseId,
            BufferedByteInput source) {
        return Boolean.TRUE;
    }
}
