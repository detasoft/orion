package pro.deta.orion.git.nativestorage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.schema.orion.v2.RepositoryName;
import pro.deta.orion.util.Result;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileNativeGitRepositoryFactoryTest {
    @Test
    void opensIndependentHandlesAndLeavesTheirLifecycleToTheCaller(@TempDir Path root) {
        NativeGitRepositoryFactory factory = new FileNativeGitRepositoryFactory(root);
        RepositoryName name = RepositoryName.parse("team/repo");
        byte[] content = "persisted".getBytes(StandardCharsets.UTF_8);
        ObjectId blob;
        try (NativeGitRepository created = factory.create(name).valueOrFailure("create")) {
            blob = created.writeObject(GitObjectType.BLOB, content);
            try (NativeGitRepository opened = factory.open(name).valueOrFailure("open")) {
                assertThat(opened).isNotSameAs(created);
                factory.close();
                assertThat(opened.readObject(blob)).hasValueSatisfying(object ->
                        assertThat(object.data()).isEqualTo(content));
            }
            assertThat(created.readObject(blob)).isPresent();
        }
    }

    @Test
    void missingOpenDoesNotPreventCreationAndDuplicateCreatePreservesContents(@TempDir Path root) {
        RepositoryName name = RepositoryName.parse("team/repo");
        try (NativeGitRepositoryFactory factory = new FileNativeGitRepositoryFactory(root)) {
            Result<NativeGitRepository> missing = factory.open(name);
            assertThat(missing).isInstanceOf(Result.Failure.class);
            assertThat(((Result.Failure<?>) missing).code()).isEqualTo(Result.FailureCode.NOT_FOUND);
            try (NativeGitRepository repository = factory.create(name).valueOrFailure("create")) {
                ObjectId blob = repository.writeObject(GitObjectType.BLOB, new byte[]{1, 2, 3});
                Result<NativeGitRepository> duplicate = factory.create(name);
                assertThat(duplicate).isInstanceOf(Result.Failure.class);
                assertThat(((Result.Failure<?>) duplicate).code())
                        .isEqualTo(Result.FailureCode.FILE_ALREADY_EXISTS);
                assertThat(repository.readObject(blob)).isPresent();
            }
        }
    }
}
