package pro.deta.orion.git.nativestorage.object;

import pro.deta.orion.git.nativestorage.GitObjectId;

import java.util.Optional;

/**
 * Reads an object by identity independently of its storage representation.
 * Empty means the object is absent; storage and decoding failures propagate.
 * The returned LooseObject contains decoded object data, including for packed objects.
 * A reader does not by itself pin storage or provide a snapshot across calls.
 */
@FunctionalInterface
public interface GitObjectReader {
    Optional<LooseObject> read(GitObjectId id);
}
