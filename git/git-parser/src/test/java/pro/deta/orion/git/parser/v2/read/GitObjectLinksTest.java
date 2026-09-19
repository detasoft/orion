package pro.deta.orion.git.parser.v2.read;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.net.io.InputStreamBufferedByteInput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitObjectLinksTest {
    @Test
    void commitIncludesTreeAndEveryMergeParentButNotMessageText() throws Exception {
        ObjectId tree = new ObjectId("1".repeat(40));
        ObjectId first = new ObjectId("2".repeat(40));
        ObjectId second = new ObjectId("3".repeat(40));
        byte[] content = ("tree " + tree.toHex() + "\nparent " + first.toHex()
                + "\nparent " + second.toHex() + "\n\nparent " + tree.toHex() + "\n")
                .getBytes(StandardCharsets.US_ASCII);
        assertThat(read(GitObjectType.COMMIT, content).targets()).containsExactly(tree, first, second);
    }

    @Test
    void treeFollowsObjectsButDoesNotFollowSubmoduleCommits() throws Exception {
        ObjectId blob = new ObjectId("1".repeat(40));
        ObjectId submodule = new ObjectId("2".repeat(40));
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        content.write("100644 name with spaces\0".getBytes(StandardCharsets.US_ASCII));
        content.write(blob.toBytes());
        content.write("160000 submodule\0".getBytes(StandardCharsets.US_ASCII));
        content.write(submodule.toBytes());
        assertThat(read(GitObjectType.TREE, content.toByteArray()).targets()).containsExactly(blob);
    }

    @Test
    void rejectsTruncatedTreeReferencesAndMalformedCommitIds() {
        assertThatThrownBy(() -> read(GitObjectType.TREE, "100644 file\0short".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> read(GitObjectType.COMMIT, ("tree " + "z".repeat(40) + "\n\n")
                .getBytes(StandardCharsets.US_ASCII))).isInstanceOf(IOException.class);
    }

    private static GitObjectLinks read(GitObjectType type, byte[] content) throws IOException {
        try (InputStreamBufferedByteInput input = new InputStreamBufferedByteInput(new ByteArrayInputStream(content))) {
            return GitObjectLinks.read(type, content.length, Optional.empty(), input);
        }
    }
}
