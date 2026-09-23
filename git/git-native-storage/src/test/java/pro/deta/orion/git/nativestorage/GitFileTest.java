package pro.deta.orion.git.nativestorage;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.data.FileMode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitFileTest {
    @Test
    void ownsContentAndKeepsSnapshotIndependentOfItsCallers() {
        byte[] content = new byte[]{1, 2};
        GitFile file = new GitFile(FileMode.EXECUTABLE_FILE, content);
        Map<String, GitFile> files = new LinkedHashMap<>(Map.of("run", file));
        GitRepositoryFileSnapshot snapshot = new GitRepositoryFileSnapshot(files, Optional.of("revision"));
        content[0] = 3;
        file.content()[1] = 4;
        files.clear();
        snapshot.files().get("run").content()[0] = 5;

        assertThat(snapshot.files()).containsExactly(Map.entry("run",
                new GitFile(FileMode.EXECUTABLE_FILE, new byte[]{1, 2})));
        assertThat(snapshot.version()).contains("revision");
        assertThatThrownBy(() -> snapshot.files().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void distinguishesContentAndModeAsFileValues() {
        GitFile regular = GitFile.regular(new byte[]{1});
        assertThat(regular).isEqualTo(GitFile.regular(new byte[]{1}));
        assertThat(regular).hasSameHashCodeAs(GitFile.regular(new byte[]{1}));
        assertThat(regular).isNotEqualTo(GitFile.regular(new byte[]{2}));
        assertThat(regular).isNotEqualTo(new GitFile(FileMode.EXECUTABLE_FILE, new byte[]{1}));
    }

    @Test
    void rejectsModesWhosePayloadIsNotFileContent() {
        for (FileMode mode : new FileMode[]{FileMode.TREE, FileMode.GITLINK}) {
            assertThatThrownBy(() -> new GitFile(mode, new byte[0]))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(mode.name());
        }
    }
}
