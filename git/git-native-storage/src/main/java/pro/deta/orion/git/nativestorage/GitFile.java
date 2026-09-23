package pro.deta.orion.git.nativestorage;

import pro.deta.orion.git.parser.v2.data.FileMode;

import java.util.Arrays;
import java.util.Objects;

/**
 * A blob-backed Git file with an explicit tree mode and owned content. Symbolic-link content is the
 * link target, not the target file's bytes. Trees and gitlinks have no file payload and are not accepted.
 * Saves use the supplied mode; files omitted from an update retain their existing mode and object ID.
 */
public record GitFile(FileMode mode, byte[] content) {
    public GitFile {
        Objects.requireNonNull(mode, "mode");
        if (mode == FileMode.TREE || mode == FileMode.GITLINK) {
            throw new IllegalArgumentException("Mode has no file content: " + mode);
        }
        content = Objects.requireNonNull(content, "content").clone();
    }

    public static GitFile regular(byte[] content) {
        return new GitFile(FileMode.REGULAR_FILE, content);
    }

    @Override
    public byte[] content() {
        return content.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof GitFile file && mode == file.mode && Arrays.equals(content, file.content);
    }

    @Override
    public int hashCode() {
        return 31 * mode.hashCode() + Arrays.hashCode(content);
    }
}
