package pro.deta.orion.git.parser.wire.exchange;

import pro.deta.orion.git.nativestorage.pack.PackIngestionResult;

import java.util.Objects;

/**
 * @deprecated Remove with native receive-pack storage; PushCommand publishes IndexedPack before updating refs.
 */
@Deprecated(forRemoval = true)
public record LegacyReceivePack(
        LegacyReceiveCommandSection commandSection,
        PackIngestionResult.Complete pack) {
    public LegacyReceivePack {
        Objects.requireNonNull(commandSection, "commandSection");
        Objects.requireNonNull(pack, "pack");
    }
}
