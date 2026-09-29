package pro.deta.orion.git.parser.v2.id;

/**
 * Checksum of the final Git-compatible pack representation; distinct from its internal storage identity.
 */
public final class PackChecksum extends GitId {
    public PackChecksum(byte[] bytes) {
        super(bytes);
    }

    public PackChecksum(String hex) {
        super(hex);
    }
}
