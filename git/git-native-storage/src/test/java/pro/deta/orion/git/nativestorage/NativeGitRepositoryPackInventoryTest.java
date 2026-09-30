package pro.deta.orion.git.nativestorage;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.local.LocalGitIndex;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.git.parser.v2.index.GitIndexApi;
import pro.deta.orion.git.parser.v2.index.memory.InMemoryIndex;
import pro.deta.orion.git.parser.v2.storage.GitStorageAccess;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.v2.storage.local.LocalGitStorage;
import pro.deta.orion.git.parser.v2.storage.memory.InMemoryStorage;
import pro.deta.orion.git.parser.v2.storage.shared.PackHandle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class NativeGitRepositoryPackInventoryTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void excludesPublishedAndActivePacksFromCleanupCandidates(boolean memory) throws Exception {
        GitStorageApi storage = memory ? new InMemoryStorage() : new LocalGitStorage(directory);
        GitIndexApi index = memory ? new InMemoryIndex() : new LocalGitIndex(directory);
        try (NativeGitRepository repository = new NativeGitRepository(
                "project.git", storage, index, "refs/heads/main")) {
            repository.writeObject(GitObjectType.BLOB, new byte[]{1});
            PackId published = index.withAccess(access -> access.packs().getFirst().packId());
            PackId orphan = PackId.create();
            PackId pending = PackId.create();
            GitIndexAccess active = index.createAccess(Optional.of(pending));
            try {
                try (GitStorageAccess bytes = storage.createAccess()) {
                    try (PackHandle handle = bytes.newPack(orphan)) {
                        handle.flush();
                    }
                    try (PackHandle handle = bytes.newPack(pending)) {
                        handle.flush();
                    }
                }
                if (!memory) {
                    Files.write(directory.resolve("packs/pack-invalid.data"), new byte[]{1});
                }
                try (GitStorageAccess bytes = storage.createAccess()) {
                    assertThat(bytes.packIds()).containsExactlyInAnyOrder(published, orphan, pending);
                }
                assertThat(repository.packCleanupCandidates()).containsExactly(orphan);
            } finally {
                active.discard();
            }
            assertThat(repository.packCleanupCandidates()).containsExactlyInAnyOrder(orphan, pending);
        }
    }
}
