package pro.deta.orion.git.parser.wire.exchange;

import pro.deta.orion.git.nativestorage.pack.PackIngestionResult;

import java.util.Objects;

public record LegacyReceivePack(
        LegacyReceiveCommandSection commandSection,
        PackIngestionResult.Complete pack) {
    public LegacyReceivePack {
        Objects.requireNonNull(commandSection, "commandSection");
        Objects.requireNonNull(pack, "pack");
    }
}
