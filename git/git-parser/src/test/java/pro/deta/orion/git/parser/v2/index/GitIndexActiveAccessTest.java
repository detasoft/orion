package pro.deta.orion.git.parser.v2.index;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.local.LocalGitIndex;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackChecksum;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.index.memory.InMemoryIndex;
import pro.deta.orion.git.parser.v2.pack.PackTestData;
import pro.deta.orion.git.parser.v2.read.GitPackRead;
import pro.deta.orion.git.parser.v2.storage.GitStorageAccess;
import pro.deta.orion.git.parser.v2.storage.shared.PackHandle;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitIndexActiveAccessTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tracksOwnershipUntilApplyOrDiscard(boolean local) throws Exception {
        try (GitIndexApi owner = index(local)) {
            assertThat(owner.activeAccesses()).isEmpty();
            PackId one = PackId.create();
            PackId two = PackId.create();
            GitIndexAccess first = owner.createAccess(Optional.of(one));
            GitIndexAccess second = owner.createAccess(Optional.of(two));
            try {
                Set<GitIndexAccess> snapshot = owner.activeAccesses();
                assertThat(snapshot).containsExactlyInAnyOrder(first, second);
                assertThat(first.packId()).contains(one);
                assertThat(second.packId()).contains(two);
                assertThatThrownBy(snapshot::clear).isInstanceOf(UnsupportedOperationException.class);
                owner.close();
                assertThat(owner.activeAccesses()).containsExactlyInAnyOrder(first, second);
                first.apply();
                assertThat(owner.activeAccesses()).containsExactly(second);
                assertThat(snapshot).containsExactlyInAnyOrder(first, second);
                second.discard();
                assertThat(owner.activeAccesses()).isEmpty();
            } finally {
                first.discard();
                second.discard();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void onlyDeclaredPackCanBePublished(boolean local) throws Exception {
        PackId declared = PackId.create();
        PackId other = PackId.create();
        PackMetadata metadata = new PackMetadata(other, new PackChecksum("a".repeat(40)), other.toString(), 0, 32);
        try (GitIndexApi owner = index(local)) {
            owner.withAccess(access -> {
                assertThat(access.packId()).isEmpty();
                assertThatThrownBy(() -> access.publishIndex(metadata)).isInstanceOf(IOException.class);
                return null;
            });
            owner.withAccess(Optional.of(declared), access -> {
                assertThat(access.packId()).contains(declared);
                assertThatThrownBy(() -> access.publishIndex(metadata)).isInstanceOf(IOException.class);
                assertThat(access.packs()).isEmpty();
                return null;
            });
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void releasesFailedAndConflictingAccesses(boolean local) throws Exception {
        RefId ref = new RefId("refs/heads/main");
        ObjectId target = new ObjectId("a".repeat(40));
        List<RefUpdate> updates = List.of(new RefUpdate(ref, Optional.empty(), Optional.of(target)));
        try (GitIndexApi owner = index(local)) {
            GitIndexAccess first = owner.createAccess(updates);
            GitIndexAccess second = owner.createAccess(updates);
            try {
                first.apply();
                assertThatThrownBy(second::apply).isInstanceOf(GitRefConflictException.class);
                assertThat(owner.activeAccesses()).isEmpty();
                assertThatThrownBy(() -> owner.createAccess(updates)).isInstanceOf(GitRefConflictException.class);
                assertThat(owner.activeAccesses()).isEmpty();
            } finally {
                first.discard();
                second.discard();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void registersPackBeforeStorageCreationAndRetainsItAfterStorageFailure(boolean local) throws Exception {
        IOException failure = new IOException("storage creation failed");
        try (GitIndexApi owner = index(local)) {
            PackId pack = PackId.create();
            owner.withAccess(Optional.of(pack), access -> {
                GitStorageAccess storage = new GitStorageAccess() {
                    public PackHandle newPack(PackId id) throws IOException {
                        assertThat(owner.activeAccesses()).contains(access);
                        assertThat(access.packId()).contains(id);
                        throw failure;
                    }
                    public <R> R readPack(PackId id, long offset, long length, GitPackRead<R> reader) {
                        throw new AssertionError("No pack should be read");
                    }
                    public boolean exists(PackId id) { return false; }
                    public Set<PackId> packIds() { return Set.of(); }
                    public void apply() { }
                    public void discard() { }
                };
                assertThatThrownBy(() -> PackTestData.ingest(PackTestData.pack(), storage, access))
                        .isSameAs(failure);
                assertThat(access.packId()).contains(pack);
                return null;
            });
            assertThat(owner.activeAccesses()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void observesOwnershipFromAnotherThreadWhileAccessRemainsOpen(boolean local) throws Exception {
        try (GitIndexApi owner = index(local);
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CyclicBarrier barrier = new CyclicBarrier(2);
            PackId pack = PackId.create();
            Future<Void> writer = executor.submit(() -> owner.withAccess(Optional.of(pack), access -> {
                barrier.await(5, TimeUnit.SECONDS);
                barrier.await(5, TimeUnit.SECONDS);
                return null;
            }));
            barrier.await(5, TimeUnit.SECONDS);
            try {
                assertThat(owner.activeAccesses()).hasSize(1);
                GitIndexAccess access = owner.activeAccesses().iterator().next();
                assertThat(access.packId()).contains(pack);
            } finally {
                barrier.await(5, TimeUnit.SECONDS);
            }
            writer.get(10, TimeUnit.SECONDS);
            assertThat(owner.activeAccesses()).isEmpty();
        }
    }

    private GitIndexApi index(boolean local) throws IOException {
        return local ? new LocalGitIndex(directory, new RefId("refs/heads/main")) : new InMemoryIndex(new RefId("refs/heads/main"));
    }
}
