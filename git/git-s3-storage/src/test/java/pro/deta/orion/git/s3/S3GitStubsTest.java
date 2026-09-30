package pro.deta.orion.git.s3;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.id.PackId;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S3GitStubsTest {
    @Test
    void indexNeverPretendsToContainEmptyRefsOrObjects() {
        S3GitIndex index = new S3GitIndex();
        assertThat(index.hashAlgorithm()).isEqualTo(GitHashAlgorithm.SHA1);
        assertThatThrownBy(index::createAccess).isInstanceOf(IOException.class)
                .hasMessageContaining("not implemented");
        assertThatThrownBy(() -> index.createAccess(Set.of(new RefId("refs/heads/main"))))
                .isInstanceOf(IOException.class).hasMessageContaining("not implemented");
        RefUpdate update = RefUpdate.fromWire("refs/heads/main", "0".repeat(40), "1".repeat(40));
        assertThatThrownBy(() -> index.createAccess(List.of(update)))
                .isInstanceOf(IOException.class).hasMessageContaining("not implemented");
    }

    @Test
    void storageRejectsEveryDataOperationAndClosesIdempotently() throws Exception {
        S3GitStorage storage = new S3GitStorage();
        PackId pack = PackId.create();
        assertThatThrownBy(() -> storage.newPack(pack)).isInstanceOf(IOException.class)
                .hasMessageContaining("not implemented");
        assertThatThrownBy(() -> storage.exists(pack)).isInstanceOf(IOException.class)
                .hasMessageContaining("not implemented");
        assertThatThrownBy(() -> storage.readPack(pack, 0, 1, (size, input) -> null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not implemented");
        storage.close();
        storage.close();
        assertThatThrownBy(() -> storage.exists(pack)).isInstanceOf(IOException.class);
    }
}
