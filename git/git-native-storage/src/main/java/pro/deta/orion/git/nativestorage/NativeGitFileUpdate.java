package pro.deta.orion.git.nativestorage;

import pro.deta.orion.git.parser.v2.data.RefUpdate;

import java.util.List;
import java.util.Objects;

/**
 * Owns the generated pack and conditional ref updates for a prepared file change.
 * Preparing the change does not publish objects or move refs; pack bytes can be read repeatedly.
 */
public record NativeGitFileUpdate(
        byte[] pack,
        List<RefUpdate> refUpdates) {
    public NativeGitFileUpdate {
        pack = Objects.requireNonNull(pack, "pack").clone();
        refUpdates = List.copyOf(refUpdates);
    }

    @Override
    public byte[] pack() {
        return pack.clone();
    }
}
