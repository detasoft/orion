package pro.deta.orion.git.nativestorage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.fileapi.GitCommitAuthor;
import pro.deta.orion.git.parser.v2.data.FileMode;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.index.GitRefConflictException;

import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitFileAccessTest {
    @TempDir
    Path directory;

    @Test
    void streamsOneFileAndReusesUntouchedTrees() throws Exception {
        try (NativeGitRepository repository = new FileNativeGitRepositoryProvider(directory)
                .create("demo").valueOrFailure("repository")) {
            repository.files().withAccess("main", "initial", GitCommitAuthor.EMPTY, fileAccess -> {
                fileAccess.write("nested/keep", new byte[]{7});
                fileAccess.apply();
                return null;
            });
            repository.files().withAccess("main", "config", GitCommitAuthor.EMPTY, access -> {
                access.write("orion.xml", FileMode.REGULAR_FILE, 3, input(1, 2, 3));
                assertThat(access.pack().objectCount()).isEqualTo(3);
                access.apply();
                return null;
            });
            assertThat(repository.files().readBytes("main", "nested/keep")).isEqualTo(new byte[]{7});
            assertThat(repository.files().readBytes("main", "orion.xml")).isEqualTo(new byte[]{1, 2, 3});
        }
    }

    @Test
    void discardsWhenCallbackFailsAndLeavesBorrowedInputOpen() throws Exception {
        try (NativeGitRepository repository = new FileNativeGitRepositoryProvider(directory)
                .create("demo").valueOrFailure("repository")) {
            InputStream input = new ByteArrayInputStream(new byte[]{1}) {
                @Override
                public void close() {
                    throw new AssertionError("Borrowed input must not be closed");
                }
            };
            IOException failure = new IOException("callback failed");
            assertThatThrownBy(() -> repository.files().withAccess("main", "config", GitCommitAuthor.EMPTY, access -> {
                access.write("orion.xml", FileMode.REGULAR_FILE, 1, new BufferedByteInputV2(input));
                throw failure;
            })).isSameAs(failure);
            assertThat(repository.refs()).isEmpty();
            repository.index().withAccess(index -> {
                assertThat(index.packs()).isEmpty();
                return null;
            });
        }
    }

    @Test
    void rejectsConcurrentUpdateAtApply() throws Exception {
        try (NativeGitRepository repository = new FileNativeGitRepositoryProvider(directory)
                .create("demo").valueOrFailure("repository")) {
            repository.files().withAccess("main", "stale", GitCommitAuthor.EMPTY, access -> {
                access.write("orion.xml", FileMode.REGULAR_FILE, 1, input(1));
                repository.files().withAccess("main", "winner", GitCommitAuthor.EMPTY, fileAccess -> {
                    fileAccess.write("orion.xml", new byte[]{2});
                    fileAccess.apply();
                    return null;
                });
                assertThatThrownBy(access::apply).isInstanceOf(GitRefConflictException.class);
                return null;
            });
            assertThat(repository.files().readBytes("main", "orion.xml")).isEqualTo(new byte[]{2});
        }
    }

    @Test
    void rejectsTruncatedInputWithoutPublishing() throws Exception {
        try (NativeGitRepository repository = new FileNativeGitRepositoryProvider(directory)
                .create("demo").valueOrFailure("repository")) {
            assertThatThrownBy(() -> repository.files().withAccess("main", "config", GitCommitAuthor.EMPTY, access -> {
                access.write("orion.xml", FileMode.REGULAR_FILE, 2, input(1));
                access.apply();
                return null;
            })).isInstanceOf(IOException.class);
            assertThat(repository.refs()).isEmpty();
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
        try (NativeGitRepository repository = new FileNativeGitRepositoryProvider(directory.resolve("repositories"))
                .create("demo").valueOrFailure("repository")) {
            repository.files().withAccess("main", "stream", GitCommitAuthor.EMPTY, access -> {
                for (String name : List.of("first", "second")) {
                    try (BufferedByteInputV2 input = new BufferedByteInputV2(Files.newInputStream(source))) {
                        access.write(name, FileMode.REGULAR_FILE, Files.size(source), input);
                        assertThat(input.buffer()).isNull();
                    }
                }
                Files.delete(source);
                assertThat(access.pack().objectCount()).isEqualTo(3);
                access.apply();
                return null;
            });
            byte[] content = repository.files().readBytes("main", "second");
            assertThat(content).hasSize(8192 * 512).containsOnly((byte) 37);
        }
    }

    @Test
    void rejectsExplicitCreationWhenTheBranchAlreadyExists() throws Exception {
        try (NativeGitRepository repository = new FileNativeGitRepositoryProvider(directory)
                .create("demo").valueOrFailure("repository")) {
            repository.files().withAccess("main", "winner", GitCommitAuthor.EMPTY, access -> {
                access.write("orion.xml", FileMode.REGULAR_FILE, 1, input(1));
                access.apply();
                return null;
            });
            String winner = repository.refs().get("refs/heads/main");
            repository.files().withAccess("main", null, "creation", GitCommitAuthor.EMPTY, access -> {
                access.write("orion.xml", FileMode.REGULAR_FILE, 1, input(2));
                assertThatThrownBy(access::apply).isInstanceOf(GitRefConflictException.class);
                return null;
            });
            assertThat(repository.refs().get("refs/heads/main")).isEqualTo(winner);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"main", "configuration"})
    void createsAnExplicitlyAbsentBranch(String branch) throws Exception {
        try (NativeGitRepository repository = new FileNativeGitRepositoryProvider(directory)
                .create("demo").valueOrFailure("repository")) {
            repository.files().withAccess(branch, null, "creation", GitCommitAuthor.EMPTY, access -> {
                access.write("material.p12", FileMode.REGULAR_FILE, 1, input(1));
                assertThat(access.refUpdates().getFirst().expectedOld()).isEmpty();
                assertThat(access.refUpdates().getFirst().newId()).isPresent();
                access.apply();
                return null;
            });
            String revision = repository.refs().get("refs/heads/" + branch);
            assertThat(revision).isNotNull().isEqualTo(repository.refs().get(repository.defaultHead()));
            byte[] bytes = repository.files().readFile(new ObjectId(revision), "material.p12",
                    (type, size, base, input) -> input.readBytes(Math.toIntExact(size)));
            assertThat(bytes).containsExactly(1);
        }
    }

    @Test
    void streamsTheRequestedRevisionAfterTheBranchMoves() throws Exception {
        try (NativeGitRepository repository = new FileNativeGitRepositoryProvider(directory)
                .create("demo").valueOrFailure("repository")) {
            repository.files().withAccess("main", "first", GitCommitAuthor.EMPTY, access -> {
                access.write("orion.xml", FileMode.REGULAR_FILE, 3, input(1, 2, 3));
                access.apply();
                return null;
            });
            ObjectId first = new ObjectId(repository.refs().get("refs/heads/main"));
            repository.files().withAccess("main", "second", GitCommitAuthor.EMPTY, access -> {
                access.write("orion.xml", FileMode.REGULAR_FILE, 1, input(4));
                access.apply();
                return null;
            });
            byte[] previous = repository.files().readFile(first, "orion.xml",
                    (type, size, base, input) -> input.readBytes(Math.toIntExact(size)));
            assertThat(previous).containsExactly(1, 2, 3);
            ObjectId current = new ObjectId(repository.refs().get("refs/heads/main"));
            byte[] latest = repository.files().readFile(current, "orion.xml",
                    (type, size, base, input) -> input.readBytes(Math.toIntExact(size)));
            assertThat(latest).containsExactly(4);
            IOException failure = new IOException("reader failed");
            assertThatThrownBy(() -> repository.files().readFile(current, "orion.xml",
                    (type, size, base, input) -> { throw failure; })).isSameAs(failure);
            assertThat(repository.refs().get("refs/heads/main")).isEqualTo(current.toHex());
        }
    }

    @Test
    void returnsCallbackResultWithoutApplyingImplicitly() throws Exception {
        try (NativeGitRepository repository = new FileNativeGitRepositoryProvider(directory)
                .create("demo").valueOrFailure("repository")) {
            String result = repository.files().withAccess("main", "discard", GitCommitAuthor.EMPTY, access -> {
                try (BufferedByteInputV2 input = input(1)) {
                    access.write("orion.xml", FileMode.REGULAR_FILE, 1, input);
                }
                return "result";
            });
            assertThat(result).isEqualTo("result");
            assertThat(repository.refs()).isEmpty();
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
