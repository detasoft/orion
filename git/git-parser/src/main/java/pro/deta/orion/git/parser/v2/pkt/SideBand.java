package pro.deta.orion.git.parser.v2.pkt;

/**
 * Optional side-band channel for Data writes; control markers are always written without a channel.
 */
public enum SideBand {
    NONE(0), DATA(1), PROGRESS(2), ERROR(3);

    private final byte wireValue;

    SideBand(int wireValue) {
        this.wireValue = (byte) wireValue;
    }

    public byte wireValue() {
        return wireValue;
    }
}
