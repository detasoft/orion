package pro.deta.orion.git.parser.wire.sideband;

import pro.deta.orion.git.parser.wire.GitFixedControlFrameReader;

import static pro.deta.orion.git.parser.wire.control.ControlState.PKT_LINE_HEADER_SIZE;

public enum GitSideBandMode {
    SIDE_BAND(1_000),
    SIDE_BAND_64K(GitFixedControlFrameReader.MAX_PKT_LINE_LENGTH);

    private static final int BAND_HEADER_SIZE = 1;

    private final int maxPacketLength;

    GitSideBandMode(int maxPacketLength) {
        this.maxPacketLength = maxPacketLength;
    }

    public int maxPacketLength() {
        return maxPacketLength;
    }

    public int maxDataBytesPerPacket() {
        return maxPacketLength - PKT_LINE_HEADER_SIZE - BAND_HEADER_SIZE;
    }
}
