package pro.deta.orion.git.nativestorage;


import pro.deta.orion.util.VcChangeDescription;
import pro.deta.orion.util.VcRevision;
import pro.deta.orion.git.fileapi.GitFileAccess;
import static pro.deta.orion.git.fileapi.GitFileTestSupport.initialize;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.util.VcChangeAuthor;
import pro.deta.orion.git.parser.v2.data.FileMode;
import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.index.GitRefConflictException;

import pro.deta.orion.net.io.BufferedByteInputV2;

import pro.deta.orion.git.parser.v2.id.ObjectId.CommitId;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitFileAccessTest {
    @org.junit.jupiter.api.extension.RegisterExtension
    final pro.deta.orion.test.util.OrionTestRuntime testRuntime =
            new pro.deta.orion.test.util.OrionTestRuntime();

    @TempDir
    Path directory;

    @Test
    void writesDifferentMessagesWithTheSameAuthor() throws Exception {
        NativeGitRepositoryProvider provider = NativeGitRepositoryProvider.inMemory(testRuntime.events());
        testRuntime.own(provider);
        NativeGitRepository repository = provider.create("metadata").valueOrFailure("repository");
        VcChangeAuthor author = new VcChangeAuthor("Ирина", "irina@example.test");
        for (String message : List.of("first commit", "обновление\nвторая строка")) {
            initialize(repository, "main");
            String revision;
            try (GitFileAccess access = repository.files().open(repository.ref("main"))) {
                access.write("file", message.getBytes(StandardCharsets.UTF_8));
                VcRevision.Stored created = access.commit(new VcChangeDescription(author, message));
                repository.refUpdate(repository.ref("main"), access.revision(), created);
                revision = created.value();
            }
            String commit = new String(repository.readObject(new ObjectId(revision)).orElseThrow().data(),
                    StandardCharsets.UTF_8);
            assertThat(commit).contains("author Ирина <irina@example.test> 0 +0000\n",
                    "committer Ирина <irina@example.test> 0 +0000\n");
            assertThat(commit).endsWith("\n\n" + message + "\n");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \t"})
    void preservesDefaultCommitIdentityAndMessage(String message) throws Exception {
        NativeGitRepositoryProvider provider = NativeGitRepositoryProvider.inMemory(testRuntime.events());
        testRuntime.own(provider);
        NativeGitRepository repository = provider.create("metadata").valueOrFailure("repository");
        initialize(repository, "main");
        String revision;
        try (GitFileAccess access = repository.files().open(repository.ref("main"))) {
            access.write("file", new byte[] {1});
            VcRevision.Stored created = access.commit(new VcChangeDescription(null, message));
            repository.refUpdate(repository.ref("main"), access.revision(), created);
            revision = created.value();
        }
        String commit = new String(repository.readObject(new ObjectId(revision)).orElseThrow().data(),
                StandardCharsets.UTF_8);
        assertThat(commit).contains("author orion <orion@localhost> 0 +0000\n",
                "committer orion <orion@localhost> 0 +0000\n");
        assertThat(commit).endsWith("\n\nupdate files\n");
    }

    @Test
    @org.junit.jupiter.api.Disabled("First-version publication: docs/plans/tasks/01_vc-first-revision.md")
    void initializesCurrentSymbolicHeadButNotDetachedHead() throws Exception {
        try (NativeGitRepository repository = NativeGitRepositoryProvider.file(directory, testRuntime.events())
                .create("demo").valueOrFailure("repository")) {
            repository.index().withAccess(index -> {
                index.updateHead(new Head.Symbolic(new RefId("refs/heads/trunk")));
                index.apply();
                return null;
            });
            assertThat(repository.index().getHEAD())
                    .isEqualTo(new Head.Symbolic(new RefId("refs/heads/trunk")));
            try (GitFileAccess access = repository.files().open(repository.ref("topic"))) {
                access.write("file", new byte[] {1});
                repository.refUpdate(
                        repository.ref("topic"),
                        access.revision(),
                        access.commit(new VcChangeDescription(VcChangeAuthor.EMPTY, "first")));
            }

            assertThat(repository.refs()).containsKeys("refs/heads/trunk", "refs/heads/topic")
                    .doesNotContainKey("refs/heads/main");

            CommitId detached = new CommitId(new ObjectId(repository.refs().get("refs/heads/topic")).toBytes());
            repository.index().withAccess(index -> {
                index.updateHead(new Head.Detached(detached));
                index.apply();
                return null;
            });
            assertThat(repository.index().getHEAD()).isEqualTo(new Head.Detached(detached));
            try (GitFileAccess access = repository.files().open(repository.ref("other"))) {
                access.write("file", new byte[] {2});
                repository.refUpdate(
                        repository.ref("other"),
                        access.revision(),
                        access.commit(new VcChangeDescription(VcChangeAuthor.EMPTY, "second")));
            }

            assertThat(repository.refs()).containsKeys("refs/heads/trunk", "refs/heads/topic", "refs/heads/other")
                    .hasSize(3);
            assertThat(repository.index().getHEAD()).isEqualTo(new Head.Detached(detached));
        }
    }

    @Test
    void streamsOneFileAndReusesUntouchedTrees() throws Exception {
        try (NativeGitRepository repository = NativeGitRepositoryProvider.file(directory, testRuntime.events())
                .create("demo").valueOrFailure("repository")) {
            initialize(repository, "main");
            try (GitFileAccess fileAccess = repository.files().open(repository.ref("main"))) {
                fileAccess.write("nested/keep", new byte[] {7});
                repository.refUpdate(
                        repository.ref("main"),
                        fileAccess.revision(),
                        fileAccess.commit(
                                new VcChangeDescription(VcChangeAuthor.EMPTY, "initial")));
            }

            initialize(repository, "main");
            try (GitFileAccess access = repository.files().open(repository.ref("main"))) {
                access.write("orion.xml", FileMode.REGULAR_FILE, 3, input(1, 2, 3));
                repository.refUpdate(
                        repository.ref("main"),
                        access.revision(),
                        access.commit(new VcChangeDescription(VcChangeAuthor.EMPTY, "config")));
            }

            assertThat(
                            repository
                                    .files()
                                    .<byte[], Exception>read(
                                            repository.ref("main"),
                                            file -> file.readBytes("nested/keep")))
                    .isEqualTo(new byte[] {7});
            assertThat(
                            repository
                                    .files()
                                    .<byte[], Exception>read(
                                            repository.ref("main"),
                                            file -> file.readBytes("orion.xml")))
                    .isEqualTo(new byte[] {1, 2, 3});
        }
    }

    @Test
    void discardsWhenCallbackFailsAndLeavesBorrowedInputOpen() throws Exception {
        try (NativeGitRepository repository = NativeGitRepositoryProvider.file(directory, testRuntime.events())
                .create("demo").valueOrFailure("repository")) {
            InputStream input = new ByteArrayInputStream(new byte[]{1}) {
                @Override
                public void close() {
                    throw new AssertionError("Borrowed input must not be closed");
                }
            };
            initialize(repository, "main");
            String base = repository.refs().get("refs/heads/main");
            int packs = repository.index().withAccess(index -> index.packs().size());
            IOException failure = new IOException("callback failed");
            assertThatThrownBy(() -> repository.files().read(repository.ref("main"), access -> {
                access.write("orion.xml", FileMode.REGULAR_FILE, 1, new BufferedByteInputV2(input));
                throw failure;
            })).isSameAs(failure);
            assertThat(repository.refs()).containsOnlyKeys("refs/heads/main").containsEntry("refs/heads/main", base);
            repository.index().withAccess(index -> {
                assertThat(index.packs()).hasSize(packs);
                return null;
            });
        }
    }

    @Test
    void rejectsConcurrentUpdateAtRefPublication() throws Exception {
        try (NativeGitRepository repository = NativeGitRepositoryProvider.file(directory, testRuntime.events())
                .create("demo").valueOrFailure("repository")) {
            initialize(repository, "main");
            try (GitFileAccess access = repository.files().open(repository.ref("main"))) {
                access.write("orion.xml", FileMode.REGULAR_FILE, 1, input(1));
                initialize(repository, "main");
                try (GitFileAccess fileAccess = repository.files().open(repository.ref("main"))) {
                    fileAccess.write("orion.xml", new byte[] {2});
                    repository.refUpdate(
                            repository.ref("main"),
                            fileAccess.revision(),
                            fileAccess.commit(
                                    new VcChangeDescription(VcChangeAuthor.EMPTY, "winner")));
                }

                assertThatThrownBy(
                                () ->
                                        repository.refUpdate(
                                                repository.ref("main"),
                                                access.revision(),
                                                access.commit(VcChangeDescription.EMPTY)))
                        .isInstanceOf(GitRefConflictException.class);
            }

            assertThat(
                            repository
                                    .files()
                                    .<byte[], Exception>read(
                                            repository.ref("main"),
                                            file -> file.readBytes("orion.xml")))
                    .isEqualTo(new byte[] {2});
        }
    }

    @Test
    void rejectsTruncatedInputWithoutPublishing() throws Exception {
        try (NativeGitRepository repository = NativeGitRepositoryProvider.file(directory, testRuntime.events())
                .create("demo").valueOrFailure("repository")) {
            initialize(repository, "main");
            String base = repository.refs().get("refs/heads/main");
            assertThatThrownBy(
                            () -> {
                                try (GitFileAccess access =
                                        repository.files().open(repository.ref("main"))) {
                                    access.write("orion.xml", FileMode.REGULAR_FILE, 2, input(1));
                                    repository.refUpdate(
                                            repository.ref("main"),
                                            access.revision(),
                                            access.commit(VcChangeDescription.EMPTY));
                                }
                            })
                    .isInstanceOf(IOException.class);
            assertThat(repository.refs()).containsEntry("refs/heads/main", base);
        }
    }

    @Test
    void consumesTemporaryFileDuringWriteAndDeduplicatesItsBlob() throws Exception {
        Path source = directory.resolve("temporary-content");
        byte[] block = new byte[8192];
        Arrays.fill(block, (byte) 37);
        try (OutputStream output = Files.newOutputStream(source)) {
            for (int count = 0; count < 512; count++) {
                output.write(block);
            }
        }
        try (NativeGitRepository repository =
                NativeGitRepositoryProvider.file(
                                directory.resolve("repositories"), testRuntime.events())
                        .create("demo")
                        .valueOrFailure("repository")) {
            initialize(repository, "main");
            try (GitFileAccess access = repository.files().open(repository.ref("main"))) {
                for (String name : List.of("first", "second")) {
                    try (BufferedByteInputV2 input =
                            new BufferedByteInputV2(Files.newInputStream(source))) {
                        access.write(name, FileMode.REGULAR_FILE, Files.size(source), input);
                        assertThat(input.buffer()).isNull();
                    }
                }
                Files.delete(source);
                repository.refUpdate(
                        repository.ref("main"),
                        access.revision(),
                        access.commit(new VcChangeDescription(VcChangeAuthor.EMPTY, "stream")));
            }

            byte[] content = repository.files().read(repository.ref("main"), file -> file.readBytes("second"));
            assertThat(content).hasSize(8192 * 512).containsOnly((byte) 37);
        }
    }

    @Test
    void rejectsExplicitCreationWhenTheBranchAlreadyExists() throws Exception {
        try (NativeGitRepository repository = NativeGitRepositoryProvider.file(directory, testRuntime.events())
                .create("demo").valueOrFailure("repository")) {
            initialize(repository, "main");
            try (GitFileAccess access = repository.files().open(repository.ref("main"))) {
                access.write("orion.xml", FileMode.REGULAR_FILE, 1, input(1));
                repository.refUpdate(
                        repository.ref("main"),
                        access.revision(),
                        access.commit(new VcChangeDescription(VcChangeAuthor.EMPTY, "winner")));
            }

            String winner = repository.refs().get("refs/heads/main");
            try (GitFileAccess access = repository.files().open(repository.ref("main"))) {
                access.write("orion.xml", FileMode.REGULAR_FILE, 1, input(2));
                VcRevision.Stored created = access.commit(VcChangeDescription.EMPTY);
                assertThatThrownBy(
                                () ->
                                        GitOperationException.requireSuccess(
                                                List.of(
                                                        repository.updateRef(
                                                                "refs/heads/main",
                                                                "0".repeat(40),
                                                                created.value()))))
                        .isInstanceOf(GitRepositoryConcurrentUpdateException.class);
            }
            assertThat(repository.refs().get("refs/heads/main")).isEqualTo(winner);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"main", "configuration"})
    void createsAnExplicitlyAbsentBranch(String branch) throws Exception {
        try (NativeGitRepository repository = NativeGitRepositoryProvider.file(directory, testRuntime.events())
                .create("demo").valueOrFailure("repository")) {
            initialize(repository, "base");
            VcRevision.Stored created;
            try (GitFileAccess access = repository.files().open(repository.ref("base"))) {
                access.write("material.p12", FileMode.REGULAR_FILE, 1, input(1));
                created = access.commit(VcChangeDescription.EMPTY);
                GitOperationException.requireSuccess(
                        List.of(
                                repository.updateRef(
                                        "refs/heads/" + branch, "0".repeat(40), created.value())));
            }
            assertThat(repository.refs()).containsEntry("refs/heads/" + branch, created.value());
            byte[] bytes = repository.files().read(created, access -> access.readFile("material.p12",
                    (type, size, base, input) -> input.readBytes(Math.toIntExact(size))));
            assertThat(bytes).containsExactly(1);
        }
    }

    @Test
    void streamsTheRequestedRevisionAfterTheBranchMoves() throws Exception {
        try (NativeGitRepository repository = NativeGitRepositoryProvider.file(directory, testRuntime.events())
                .create("demo").valueOrFailure("repository")) {
            initialize(repository, "main");
            try (GitFileAccess access = repository.files().open(repository.ref("main"))) {
                access.write("orion.xml", FileMode.REGULAR_FILE, 3, input(1, 2, 3));
                repository.refUpdate(
                        repository.ref("main"),
                        access.revision(),
                        access.commit(new VcChangeDescription(VcChangeAuthor.EMPTY, "first")));
            }

            ObjectId first = new ObjectId(repository.refs().get("refs/heads/main"));
            initialize(repository, "main");
            try (GitFileAccess access = repository.files().open(repository.ref("main"))) {
                access.write("orion.xml", FileMode.REGULAR_FILE, 1, input(4));
                repository.refUpdate(
                        repository.ref("main"),
                        access.revision(),
                        access.commit(new VcChangeDescription(VcChangeAuthor.EMPTY, "second")));
            }

            byte[] previous = repository.files().read(new VcRevision.Stored(first.toHex()), access -> access.readFile("orion.xml",
                    (type, size, base, input) -> input.readBytes(Math.toIntExact(size))));
            assertThat(previous).containsExactly(1, 2, 3);
            ObjectId current = new ObjectId(repository.refs().get("refs/heads/main"));
            byte[] latest = repository.files().read(new VcRevision.Stored(current.toHex()), access -> access.readFile("orion.xml",
                    (type, size, base, input) -> input.readBytes(Math.toIntExact(size))));
            assertThat(latest).containsExactly(4);
            IOException failure = new IOException("reader failed");
            assertThatThrownBy(
                            () ->
                                    repository
                                            .files()
                                            .read(
                                                    new VcRevision.Stored(current.toHex()),
                                                    access ->
                                                            access.readFile(
                                                                    "orion.xml",
                                                                    (type, size, base, input) -> {
                                                                        throw failure;
                                                                    })))
                    .isSameAs(failure);
            assertThat(repository.refs().get("refs/heads/main")).isEqualTo(current.toHex());
        }
    }

    @Test
    void returnsCallbackResultWithoutApplyingImplicitly() throws Exception {
        try (NativeGitRepository repository = NativeGitRepositoryProvider.file(directory, testRuntime.events())
                .create("demo").valueOrFailure("repository")) {
            initialize(repository, "main");
            String base = repository.refs().get("refs/heads/main");
            String result = repository.files().read(repository.ref("main"), access -> {
                try (BufferedByteInputV2 input = input(1)) {
                    access.write("orion.xml", FileMode.REGULAR_FILE, 1, input);
                }
                return "result";
            });
            assertThat(result).isEqualTo("result");
            assertThat(repository.refs()).containsEntry("refs/heads/main", base);
            assertThatThrownBy(() -> repository.files().read(repository.ref("main"),
                    access -> access.readBytes("orion.xml"))).isInstanceOf(GitRepositoryFileNotFoundException.class);
        }
    }

    private static BufferedByteInputV2 input(int... content) {
        byte[] bytes = new byte[content.length];
        for (int index = 0; index < content.length; index++) {
            bytes[index] = (byte) content[index];
        }
        return new BufferedByteInputV2(new ByteArrayInputStream(bytes));
    }
}
