package pro.deta.orion.git.nativestorage;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.fileapi.GitFile;
import pro.deta.orion.git.parser.v2.data.FileMode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitFileTest {
    @Test
    void ownsContentAndKeepsLoadedFilesIndependentOfItsCallers() throws Exception {
        byte[] content = new byte[]{1, 2};
        GitFile file = new GitFile(FileMode.EXECUTABLE_FILE, content);
        try (NativeGitRepository repository = new InMemoryNativeGitRepositoryProvider()
                .create("demo").valueOrFailure("repository")) {
            Map<String, GitFile> files = new LinkedHashMap<>(Map.of("run", file));
            repository.files().saveFiles("main", files, java.util.Set.of(), "initial",
                    pro.deta.orion.git.fileapi.GitCommitAuthor.EMPTY);
            Map<String, GitFile> loaded = repository.files().loadFiles("main", java.util.List.of("run"));
            content[0] = 3;
            file.content()[1] = 4;
            files.clear();
            loaded.get("run").content()[0] = 5;
            assertThat(loaded).containsExactly(Map.entry("run",
                    new GitFile(FileMode.EXECUTABLE_FILE, new byte[]{1, 2})));
            assertThatThrownBy(loaded::clear).isInstanceOf(UnsupportedOperationException.class);
        }
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
