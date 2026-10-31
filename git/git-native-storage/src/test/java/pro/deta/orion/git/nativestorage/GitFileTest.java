package pro.deta.orion.git.nativestorage;

import pro.deta.orion.util.VcChangeAuthor;

import pro.deta.orion.util.VcChangeDescription;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.data.FileMode;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import pro.deta.orion.git.parser.v2.id.ObjectId;

class GitFileTest {
    @org.junit.jupiter.api.extension.RegisterExtension
    final pro.deta.orion.test.util.OrionTestRuntime testRuntime =
            new pro.deta.orion.test.util.OrionTestRuntime();

    @Test
    void writesConsumeBytesImmediatelyAndReadsHaveIndependentContent() throws Exception {
        byte[] content = new byte[]{1, 2};
        try (NativeGitRepository repository = NativeGitRepositoryProvider.inMemory(testRuntime.events())
                .create("demo").valueOrFailure("repository")) {
            repository
                    .files()
                    .withAccess(
                            "main",
                            new VcChangeDescription(VcChangeAuthor.EMPTY, "initial"),
                            access -> {
                                access.write("run", FileMode.EXECUTABLE_FILE, content);
                                content[0] = 3;
                                access.apply();
                                return null;
                            });
            byte[] loaded = repository.files().readBytes("main", "run");
            assertThat(loaded).containsExactly(1, 2);
            loaded[0] = 5;
            ObjectId revision = new ObjectId(repository.refs().get("refs/heads/main"));
            assertThat(repository.files().readBytes(revision, "run")).containsExactly(1, 2);
        }
    }

    @Test
    void rejectsModesWhosePayloadIsNotFileContentWithoutPublishing() throws Exception {
        try (NativeGitRepository repository = NativeGitRepositoryProvider.inMemory(testRuntime.events())
                .create("demo").valueOrFailure("repository")) {
            for (FileMode mode : new FileMode[]{FileMode.TREE, FileMode.GITLINK}) {
                assertThatThrownBy(
                                () ->
                                        repository
                                                .files()
                                                .withAccess(
                                                        "main",
                                                        new VcChangeDescription(VcChangeAuthor.EMPTY, "invalid"),
                                                        access -> {
                                                            access.write("file", mode, new byte[0]);
                                                            access.apply();
                                                            return null;
                                                        }))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThat(repository.refs()).isEmpty();
        }
    }
}
