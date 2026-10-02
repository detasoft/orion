package pro.deta.orion.transport.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.storage.GitStorageAccess;
import pro.deta.orion.git.parser.v2.storage.shared.PackHandle;
import pro.deta.orion.schema.orion.v2.RepositoryName;
import pro.deta.orion.util.Result;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GitPackCleanupTaskTest {
    @TempDir
    Path directory;

    @Test
    void recurringRunDeletesExpiredLocalPackAndRecordsNextAttempt() throws Exception {
        try (NativeGitRepository repository = NativeGitRepository.openLocal(
                RepositoryName.parse("team/repo"), directory, "refs/heads/main")) {
            PackId orphan = PackId.create();
            GitStorageAccess bytes = repository.storage().createAccess();
            try (PackHandle handle = bytes.newPack(orphan)) {
                handle.flush();
            }
            bytes.apply();
            Path pack = directory.resolve("packs/pack-" + orphan + ".data");
            Instant now = Instant.parse("2026-10-01T12:00:00Z");
            Files.setLastModifiedTime(pack, FileTime.from(now.minusSeconds(25 * 60 * 60)));
            GitPackCleanupTask task = new GitPackCleanupTask(provider(repository));

            GitPackCleanupTask.Status status = task.runOnce(now);

            assertThat(Files.exists(pack)).isFalse();
            assertThat(status.state()).isEqualTo("scheduled");
            assertThat(status.deleted()).isEqualTo(1);
            assertThat(status.lastAttempt()).isEqualTo("2026-10-01T12:00:00Z");
            assertThat(status.nextAttempt()).isEqualTo("2026-10-01T13:00:00Z");
            assertThat(task.runOnce(now.plusSeconds(3600)).deleted()).isZero();
        }
    }

    @Test
    void nonLocalPackIsObservedWithoutDeletion() throws Exception {
        try (NativeGitRepository repository = NativeGitRepository.createInMemory(
                RepositoryName.parse("team/repo"))) {
            PackId orphan = PackId.create();
            GitStorageAccess bytes = repository.storage().createAccess();
            try (PackHandle handle = bytes.newPack(orphan)) {
                handle.flush();
            }
            bytes.apply();
            GitPackCleanupTask.Status status = new GitPackCleanupTask(provider(repository))
                    .runOnce(Instant.parse("2026-10-01T12:00:00Z"));
            assertThat(status.observed()).isEqualTo(1);
            assertThat(status.deleted()).isZero();
            assertThat(repository.packCleanupCandidates()).containsExactly(orphan);
        }
    }

    private static NativeGitRepositoryProvider provider(NativeGitRepository repository) {
        return new NativeGitRepositoryProvider() {
            public List<String> repositoryNames() { return List.of(repository.name()); }
            public boolean exists(String name) { return repository.name().equals(name); }
            public Result<NativeGitRepository> find(String name) { return Result.of(repository); }
            public Result<NativeGitRepository> create(String name) {
                throw new UnsupportedOperationException();
            }
            public void close() {}
        };
    }
}
