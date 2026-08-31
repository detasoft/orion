package pro.deta.orion.git.parser.wire.exchange;

import pro.deta.orion.git.nativestorage.object.LooseObjectStore;

import java.util.Objects;

public record LegacyReceivePack(
        LegacyReceiveCommandSection commandSection,
        LooseObjectStore quarantine) {
    public LegacyReceivePack {
        Objects.requireNonNull(commandSection, "commandSection");
        Objects.requireNonNull(quarantine, "quarantine");
    }
}
