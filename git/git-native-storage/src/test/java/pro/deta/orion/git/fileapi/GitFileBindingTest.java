package pro.deta.orion.git.fileapi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.parser.v2.id.ObjectId.CommitId;
import pro.deta.orion.git.parser.v2.index.GitRefConflictException;
import pro.deta.orion.test.util.OrionTestRuntime;
import pro.deta.orion.util.VcChangeAuthor;
import pro.deta.orion.util.VcChangeDescription;
import pro.deta.orion.util.VcRevision;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitFileBindingTest {
    @RegisterExtension
    final OrionTestRuntime runtime = new OrionTestRuntime();

    private static final String REF = "refs/heads/main";
    private static final VcChangeDescription METADATA = new VcChangeDescription(VcChangeAuthor.EMPTY, "update");

    @Test
    void readsPinnedRevisionsAndRejectsStalePublication() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        NativeGitRepositoryProvider provider = new NativeGitRepositoryProvider(
                pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend.inMemory(runtime.events())) {
            @Override
            public pro.deta.orion.util.Result<NativeGitRepository> openForRead(String name) {
                opens.incrementAndGet();
                return super.openForRead(name);
            }
        };
        runtime.own(provider);
        NativeGitRepository repository = provider.create("repository").valueOrFailure("create repository");
        GitFile file = new GitFile(provider, "repository", REF, "config/material");
        assertThat(file.revision()).isEmpty();
        CommitId first = file.write(null, bytes("first"), METADATA);
        CommitId second = file.write(first, bytes("second"), METADATA);
        int refreshed = opens.get();
        assertThat(file.read(first)).isEqualTo(bytes("first"));
        assertThat(file.read(second)).isEqualTo(bytes("second"));
        assertThat(opens).hasValue(refreshed);
        assertThatThrownBy(() -> file.write(first, bytes("stale"), METADATA))
                .isInstanceOf(GitRefConflictException.class);
        assertThat(file.revision()).contains(second);
        GitOperationException.requireSuccess(
                List.of(repository.updateRef(REF, second.toHex(), "0".repeat(40))));
        assertThatThrownBy(() -> file.write(second, bytes("stale after deletion"), METADATA))
                .isInstanceOfSatisfying(GitRefConflictException.class,
                        conflict -> assertThat(conflict.actual()).isEmpty());
        assertThat(file.revision()).isEmpty();
        assertThat(file.read(second)).isEqualTo(bytes("second"));
    }

    @Test
    void notifiesOnlyForThisFileIncludingDeletionAndDetachesOnClose() throws Exception {
        NativeGitRepositoryProvider provider = NativeGitRepositoryProvider.inMemory(runtime.events());
        runtime.own(provider);
        NativeGitRepository repository = provider.create("repository").valueOrFailure("create repository");
        GitFile file = new GitFile(provider, "repository", REF, "material");
        CommitId first = file.write(seed(repository, REF), bytes("first"), METADATA);
        runtime.awaitEvents();
        List<CommitId> changes = new CopyOnWriteArrayList<>();
        GitFileEvents events = new GitFileEvents(file);
        runtime.own(events);
        runtime.events().forTarget(this).subscribe(GitFileChanged.class, events.source(),
                event -> changes.add(event.revision()));
        try {
            try (GitFileAccess access = repository.files().open(repository.index().resolve(repository.ref(REF)))) {
                access.write("other", bytes("unrelated"));
                repository.refUpdate(
                        repository.ref(REF), access.revision(), access.commit(METADATA));
            }
            seed(repository, "refs/heads/other");
            try (GitFileAccess access =
                    repository.files().open(repository.index().resolve(repository.ref("refs/heads/other")))) {
                access.write("material", bytes("other branch"));
                repository.refUpdate(
                        repository.ref("refs/heads/other"),
                        access.revision(),
                        access.commit(METADATA));
            }
            NativeGitRepository other = provider.create("other").valueOrFailure("create other repository");
            seed(other, REF);
            try (GitFileAccess access = other.files().open(other.index().resolve(other.ref(REF)))) {
                access.write("material", bytes("other repository"));
                other.refUpdate(other.ref(REF), access.revision(), access.commit(METADATA));
            }
            runtime.awaitEvents();
            assertThat(changes).isEmpty();
            assertThat(file.isCurrent(first)).isTrue();
            CommitId second = file.write(first, bytes("second"), METADATA);
            assertThat(file.isCurrent(first)).isFalse();
            CommitId deleted;
            try (GitFileAccess access = repository.files().open(repository.index().resolve(repository.ref(REF)))) {
                access.delete("material");
                VcRevision.Stored created = access.commit(METADATA);
                repository.refUpdate(repository.ref(REF), access.revision(), created);
                deleted = new CommitId(created.value());
            }
            runtime.awaitEvents();
            assertThat(changes).containsExactly(second, deleted);
            assertThat(file.read(first)).isEqualTo(bytes("first"));
            events.close();
            file.write(deleted, bytes("restored"), METADATA);
            runtime.awaitEvents();
            assertThat(changes).containsExactly(second, deleted);
        } finally {
            events.close();
        }
    }

    private static CommitId seed(NativeGitRepository repository, String ref) throws Exception {
        pro.deta.orion.git.parser.v2.id.ObjectId tree = repository.writeObject(
                pro.deta.orion.git.parser.v2.data.GitObjectType.TREE, new byte[0]);
        pro.deta.orion.git.parser.v2.id.ObjectId commit = repository.writeObject(
                pro.deta.orion.git.parser.v2.data.GitObjectType.COMMIT,
                bytes("tree " + tree.toHex() + "\nauthor Test <test@example.invalid> 1 +0000\n"
                        + "committer Test <test@example.invalid> 1 +0000\n\nseed\n"));
        pro.deta.orion.git.nativestorage.GitOperationException.requireSuccess(
                List.of(repository.updateRef(ref, "0".repeat(40), commit.toHex())));
        return new CommitId(commit.toHex());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
