package pro.deta.orion.git.nativestorage;

import pro.deta.orion.git.fileapi.GitFileTestSupport;

import pro.deta.orion.git.fileapi.GitFileAccess;

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
            GitFileTestSupport.initialize(repository, "main");
            try (GitFileAccess access = repository.files().open(repository.ref("main"))) {
                access.write("run", FileMode.EXECUTABLE_FILE, content);
                content[0] = 3;
                repository.refUpdate(
                        repository.ref("main"),
                        access.revision(),
                        access.commit(new VcChangeDescription(VcChangeAuthor.EMPTY, "initial")));
            }

            byte[] loaded = repository.files().read(repository.ref("main"), file -> file.readBytes("run"));
            assertThat(loaded).containsExactly(1, 2);
            loaded[0] = 5;
            ObjectId revision = new ObjectId(repository.refs().get("refs/heads/main"));
            assertThat(
                            repository
                                    .files()
                                    .<byte[], Exception>read(
                                            new pro.deta.orion.util.VcRevision.Stored(
                                                    revision.toHex()),
                                            file -> file.readBytes("run")))
                    .containsExactly(1, 2);
        }
    }

    @Test
    void rejectsModesWhosePayloadIsNotFileContentWithoutPublishing() throws Exception {
        try (NativeGitRepository repository = NativeGitRepositoryProvider.inMemory(testRuntime.events())
                .create("demo").valueOrFailure("repository")) {
            GitFileTestSupport.initialize(repository, "main");
            String base = repository.refs().get("refs/heads/main");
            for (FileMode mode : new FileMode[]{FileMode.TREE, FileMode.GITLINK}) {
                assertThatThrownBy(
                                () -> {
                                    GitFileTestSupport.initialize(repository, "main");
                                    try (GitFileAccess access =
                                            repository.files().open(repository.ref("main"))) {
                                        access.write("file", mode, new byte[0]);
                                        repository.refUpdate(
                                                repository.ref("main"),
                                                access.revision(),
                                                access.commit(
                                                        new VcChangeDescription(
                                                                VcChangeAuthor.EMPTY, "invalid")));
                                    }
                                })
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThat(repository.refs()).containsEntry("refs/heads/main", base);
        }
    }
}
