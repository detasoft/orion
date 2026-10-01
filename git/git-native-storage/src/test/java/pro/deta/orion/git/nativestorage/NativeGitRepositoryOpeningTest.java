package pro.deta.orion.git.nativestorage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.schema.orion.RepositoryName;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class NativeGitRepositoryOpeningTest {
    @Test
    void createsIndependentMemoryRepositories() {
        RepositoryName name = RepositoryName.parse("team/repo");
        byte[] content = "memory".getBytes(StandardCharsets.UTF_8);
        try (NativeGitRepository first = NativeGitRepository.createInMemory(name);
             NativeGitRepository second = NativeGitRepository.createInMemory(name)) {
            ObjectId blob = first.writeObject(GitObjectType.BLOB, content);

            assertThat(first.name()).isEqualTo(name.value());
            assertThat(first.defaultHead()).isEqualTo("refs/heads/main");
            assertThat(first.readObject(blob)).hasValueSatisfying(object ->
                    assertThat(object.data()).isEqualTo(content));
            assertThat(second.readObject(blob)).isEmpty();
        }
    }

    @Test
    void reopensLocalObjectsAndRefsAfterClose(@TempDir Path directory) throws Exception {
        RepositoryName name = RepositoryName.parse("team/repo");
        String head = "refs/heads/trunk";
        byte[] content = "persistent".getBytes(StandardCharsets.UTF_8);
        ObjectId blob;
        try (NativeGitRepository repository = NativeGitRepository.openLocal(name, directory, head)) {
            assertThat(repository.index().getHEAD()).isEqualTo(new Head.Symbolic(new RefId(head)));
            blob = repository.writeObject(GitObjectType.BLOB, content);
            repository.updateRef(head, "0".repeat(40), blob.toHex());
        }

        try (NativeGitRepository reopened = NativeGitRepository.openLocal(name, directory, head)) {
            assertThat(reopened.name()).isEqualTo(name.value());
            assertThat(reopened.defaultHead()).isEqualTo(head);
            assertThat(reopened.index().getHEAD()).isEqualTo(new Head.Symbolic(new RefId(head)));
            assertThat(reopened.refs()).containsEntry(head, blob.toHex());
            assertThat(reopened.readObject(blob)).hasValueSatisfying(object ->
                    assertThat(object.data()).isEqualTo(content));
        }
    }
}
