package pro.deta.orion.git.parser.v2.id;

/**
 * Identifies a pack by its checksum.
 */
public final class PackId extends GitId {
    public PackId(byte[] bytes) {
        super(bytes);
    }

    public PackId(String hex) {
        super(hex);
    }
}
