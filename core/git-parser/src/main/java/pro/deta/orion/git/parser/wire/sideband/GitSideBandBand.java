package pro.deta.orion.git.parser.wire.sideband;

import pro.deta.orion.git.parser.wire.GitWireError;
import pro.deta.orion.git.parser.wire.GitWireException;

public enum GitSideBandBand {
    DATA(1),
    PROGRESS(2),
    FATAL(3);

    private final int id;

    GitSideBandBand(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    static GitSideBandBand fromId(int id, long packetIndex, long byteOffset) {
        for (GitSideBandBand band : values()) {
            if (band.id == id) {
                return band;
            }
        }
        throw GitWireException.of(
                GitWireError.Kind.INVALID_SIDE_BAND,
                GitWireError.Phase.SIDE_BAND,
                packetIndex,
                byteOffset,
                "Invalid Git side-band id " + id);
    }
}
