package pro.deta.orion.git.nativestorage;

import org.junit.jupiter.api.Test;
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
import java.nio.file.attribute.FileTime;
import java.time.Instant;
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
                {
                    GitStorageAccess bytes = storage.createAccess();
                    try {
                        try (PackHandle handle = bytes.newPack(orphan)) {
                            handle.flush();
                        }
                        try (PackHandle handle = bytes.newPack(pending)) {
                            handle.flush();
                        }
                        bytes.apply();
                    } finally {
                        bytes.discard();
                    }
                }
                if (!memory) {
                    Files.write(directory.resolve("packs/pack-invalid.data"), new byte[]{1});
                }
                {
                    GitStorageAccess bytes = storage.createAccess();
                    try {
                        assertThat(bytes.packIds()).containsExactlyInAnyOrder(published, orphan, pending);
                    } finally {
                        bytes.discard();
                    }
                }
                assertThat(repository.packCleanupCandidates()).containsExactly(orphan);
            } finally {
                active.discard();
            }
            assertThat(repository.packCleanupCandidates()).containsExactlyInAnyOrder(orphan, pending);
        }
    }

    @Test
    void deletesOnlyExpiredUnpublishedLocalPacksWhenIndexIsIdle() throws Exception {
        try (NativeGitRepository repository = new NativeGitRepository("project.git",
                new LocalGitStorage(directory), new LocalGitIndex(directory), "refs/heads/main")) {
            repository.writeObject(GitObjectType.BLOB, new byte[]{1});
            PackId published = repository.index().withAccess(access -> access.packs().getFirst().packId());
            PackId expired = PackId.create();
            PackId recent = PackId.create();
            {
                GitStorageAccess bytes = repository.storage().createAccess();
                try {
                    try (PackHandle handle = bytes.newPack(expired)) { handle.flush(); }
                    try (PackHandle handle = bytes.newPack(recent)) { handle.flush(); }
                    bytes.apply();
                } finally {
                    bytes.discard();
                }
            }
            Instant now = Instant.now();
            Files.setLastModifiedTime(packPath(expired), FileTime.from(now.minusSeconds(25 * 60 * 60)));
            Files.setLastModifiedTime(packPath(published), FileTime.from(now.minusSeconds(25 * 60 * 60)));
            GitIndexAccess active = repository.index().createAccess(Optional.of(recent));
            try {
                assertThat(repository.deleteExpiredLocalPacks(now.minusSeconds(24 * 60 * 60))).isEmpty();
                assertThat(Files.exists(packPath(expired))).isTrue();
            } finally {
                active.discard();
            }
            assertThat(repository.deleteExpiredLocalPacks(now.minusSeconds(24 * 60 * 60)))
                    .hasValue(1);
            assertThat(Files.exists(packPath(expired))).isFalse();
            assertThat(Files.exists(packPath(recent))).isTrue();
            assertThat(Files.exists(packPath(published))).isTrue();
            assertThat(repository.deleteExpiredLocalPacks(now.minusSeconds(24 * 60 * 60)))
                    .hasValue(0);
        }
    }

    private Path packPath(PackId id) {
        return directory.resolve("packs/pack-" + id + ".data");
    }
}
